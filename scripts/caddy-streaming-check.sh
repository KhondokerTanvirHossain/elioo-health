#!/usr/bin/env bash
#
# Does a streamed medioo-web response still stream through Caddy's `encode zstd gzip`? (PO ruling P3, 2026-10-23)
#
#   bash scripts/caddy-streaming-check.sh [docs/infra/Caddyfile.medioo]
#   CADDY_STREAMING_LOCAL=1 bash scripts/caddy-streaming-check.sh   # no Docker: the `caddy` on PATH, once
#
# Since P3 Next sends every body unencoded (compress: false) and Caddy compresses medioo-web's responses. A page that
# streams (the paper page's shell first, then each slow Java read behind its Suspense boundary) must still reach the
# phone part by part: a Caddy that held the encoded response until the end would make the page wait for its slowest
# read, and every byte count would stay the same. This asks the question of a real Caddy:
# - the Caddyfile is the DR-42 replica scripts/budget-behind-caddy.sh builds (BUDGET_REPLICA_ONLY=1): the repo's
#   medioo.eliooo.org block as written, its `encode zstd gzip` and every handle included, with only the site address
#   and the upstreams changed, every upstream being medioo-web:3000;
# - medioo-web is scripts/caddy-streaming.mjs's upstream: over 512 B of a page, a 1.5 s hold, then the rest;
# - each path below is asked once per encoding a browser is answered in (zstd for Chrome, gzip for a browser that
#   offers no zstd), and the first part must decode well before the hold ends;
# - in every Caddy version the repo pins: the box's (scripts/caddy-route-probe.sh) and the DR-42 replica's
#   (scripts/budget-behind-caddy.sh), read from those scripts, so a version bump there is checked here too.
# Prints one PASS or FAIL line per check (paths, encodings and times only) and exits 0 only when every one passes.
# Tested both ways by scripts/test_caddy_streaming.sh. Needs docker (or caddy), curl and Node 22.15 or later.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CADDYFILE="${1:-$ROOT/docs/infra/Caddyfile.medioo}"
STREAMER="$ROOT/scripts/caddy-streaming.mjs"
# A path on a medioo-web handle (the preview, where the new screens are served before their flips) and one on the
# default handle (Java's until each flip): the block's one encode covers both.
PATHS="/app/preview/stream-check /app/stream-check"
# <Accept-Encoding the browser sends>|<the encoding Caddy must answer in>
ENCODINGS="gzip, deflate, br, zstd|zstd
gzip, deflate, br|gzip"
SUFFIX="$$-$RANDOM"
NET="medioo-stream-$SUFFIX"
UP="medioo-stream-up-$SUFFIX"
CADDY="medioo-stream-caddy-$SUFFIX"
WORK="$(mktemp -d)"
LOCAL_PIDS=""
cleanup() {
  docker rm -f "$CADDY" "$UP" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  [ -n "$LOCAL_PIDS" ] && kill $LOCAL_PIDS 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT
die() { echo "caddy-streaming-check: $*"; exit 1; }

[ -r "$CADDYFILE" ] || die "cannot read $CADDYFILE"
command -v curl >/dev/null 2>&1 || die "curl is required"
node -e 'process.exit(typeof require("node:zlib").createZstdDecompress === "function" ? 0 : 1)' 2>/dev/null \
  || die "Node 22.15 or later is required (zstd)"

# 1. The replica, exactly as the DR-42 enforcing run builds it (it refuses a block without the encode line).
BUDGET_REPLICA_ONLY=1 BUDGET_CADDYFILE="$CADDYFILE" bash "$ROOT/scripts/budget-behind-caddy.sh" > "$WORK/replica" 2> "$WORK/replica.err" \
  || die "the DR-42 replica refused $CADDYFILE: $(cat "$WORK/replica.err")"
head -n 1 "$WORK/replica" | grep -qx 'http://:8080 {' || die "the replica does not start with its address"

checks=0
failed=0
# ask <label> <base url>: every path, every encoding
ask() {
  local label="$1" base="$2" path accept expected line
  for path in $PATHS; do
    while IFS='|' read -r accept expected; do
      checks=$((checks + 1))
      line=$(node "$STREAMER" client "$base$path" "$accept" "$expected" 2>&1) || failed=$((failed + 1))
      echo "$line ($label)"
    done <<< "$ENCODINGS"
  done
}

# waits until <base url> answers anything through Caddy, up to 30 s
await() {
  for _ in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1/app/stream-check" 2>/dev/null || true)
    [ -n "$code" ] && [ "$code" != 000 ] && return 0
    sleep 1
  done
  return 1
}

if [ "${CADDY_STREAMING_LOCAL:-}" = 1 ]; then
  # 2a. No Docker: the local caddy and node, each on a free 127.0.0.1 port.
  command -v caddy >/dev/null 2>&1 || die "CADDY_STREAMING_LOCAL=1 needs caddy on PATH"
  free_port() { node -e 'const s=require("node:net").createServer().listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }
  UPORT=$(free_port)
  CPORT=$(free_port)
  STREAM_HOST=127.0.0.1 node "$STREAMER" upstream "$UPORT" > "$WORK/up.log" 2>&1 &
  LOCAL_PIDS="$!"
  disown "$!"
  { printf '{\n\tadmin off\n}\n'
    sed -e "1s|^http://:8080 {\$|http://127.0.0.1:$CPORT {|" -e "s|medioo-web:3000|127.0.0.1:$UPORT|" "$WORK/replica"; } > "$WORK/Caddyfile"
  caddy run --config "$WORK/Caddyfile" --adapter caddyfile > "$WORK/caddy.log" 2>&1 &
  LOCAL_PIDS="$LOCAL_PIDS $!"
  disown "$!"
  await "http://127.0.0.1:$CPORT" || { tail -n 20 "$WORK/caddy.log"; die "the local Caddy never answered"; }
  ask "local $(caddy version | cut -d' ' -f1)" "http://127.0.0.1:$CPORT"
else
  # 2b. Docker: the upstream on a private network under the name the replica proxies to, and each pinned Caddy in front.
  command -v docker >/dev/null 2>&1 || die "docker is required (or CADDY_STREAMING_LOCAL=1)"
  pin() { sed -n "s/^$1=\"\\(.*\\)\"\$/\\1/p" "$2" | head -n 1; }
  NODE_IMAGE=$(pin STUB_IMAGE "$ROOT/scripts/budget-behind-caddy.sh")
  BOX_CADDY=$(pin CADDY_IMAGE "$ROOT/scripts/caddy-route-probe.sh")
  REPLICA_CADDY=$(pin CADDY_IMAGE "$ROOT/scripts/budget-behind-caddy.sh")
  [ -n "$NODE_IMAGE" ] && [ -n "$BOX_CADDY" ] && [ -n "$REPLICA_CADDY" ] || die "could not read the pinned images"
  docker network create "$NET" >/dev/null || die "could not create a network"
  docker run -d --name "$UP" --network "$NET" --network-alias medioo-web \
    -v "$STREAMER:/stream/caddy-streaming.mjs:ro" "$NODE_IMAGE" node /stream/caddy-streaming.mjs upstream 3000 >/dev/null \
    || die "could not start the upstream ($NODE_IMAGE)"
  for image in $(printf '%s\n%s\n' "$BOX_CADDY" "$REPLICA_CADDY" | awk '!seen[$0]++'); do
    docker create --name "$CADDY" --network "$NET" -p 127.0.0.1::8080 "$image" >/dev/null 2>&1 \
      || { docker pull -q "$image" >/dev/null 2>&1 && docker create --name "$CADDY" --network "$NET" -p 127.0.0.1::8080 "$image" >/dev/null; } \
      || die "could not create a Caddy container from $image"
    docker cp "$WORK/replica" "$CADDY:/etc/caddy/Caddyfile" >/dev/null || die "could not copy the replica into Caddy"
    docker start "$CADDY" >/dev/null || die "could not start Caddy ($image)"
    port=$(docker port "$CADDY" 8080/tcp 2>/dev/null | head -n 1 | sed 's/.*://')
    [ -n "$port" ] || { docker logs --tail 20 "$CADDY" 2>&1; die "Caddy publishes no port ($image)"; }
    await "http://127.0.0.1:$port" || { docker logs --tail 20 "$CADDY" 2>&1; die "Caddy never answered ($image)"; }
    ask "${image%%@*}" "http://127.0.0.1:$port"
    docker rm -f "$CADDY" >/dev/null 2>&1
  done
fi

echo "caddy-streaming-check: $checks checks, $failed failed"
[ "$checks" -gt 0 ] || die "asked nothing"
[ "$failed" = 0 ]
