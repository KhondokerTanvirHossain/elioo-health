#!/usr/bin/env bash
#
# The enforcing DR-42 budget run (plan W1d; DR-42 item 2, "Where the enforcing run happens"): the built standalone
# medioo-web image behind a Caddy replica of docs/infra/Caddyfile.medioo, and e2e/budget.spec.ts run against that Caddy
# with E2E_VIA_CADDY=1. This is the setup the 203,969 B reference was taken in (the W1a measurement), so it is the run
# whose numbers the ceilings in medioo-web/e2e/budget-ceilings.json are about.
#
#   bash scripts/budget-behind-caddy.sh
#
# The replica is the repo's medioo.eliooo.org block, every line as written (its `encode zstd gzip` and header block
# included), with only two kinds of change, each checked below:
#   - the site address becomes `http://:8080` (plain http inside the Caddy container);
#   - every `reverse_proxy` upstream becomes medioo-web:3000. The Java upstream (medscribe-ai:8086) is pointed at the
#     web container too, so the pages it would send to Java today reach the Next build under test, as W1a's
#     single-upstream replica did. (W3 points it at the e2e Java stub instead, per the screens plan.)
# The spec then requires Strict-Transport-Security on every measured document, which only that header block sets:
# a run that bypassed Caddy cannot pass.
#
# Env: BUDGET_WEB_IMAGE=<image> uses an image already built instead of building medioo-web/Dockerfile here.
#      BUDGET_REPLICA_ONLY=1 prints the replica Caddyfile and exits (BUDGET_CADDYFILE=<file> reads another source);
#      scripts/test_budget_replica.sh uses it to test the replica both ways without Docker.
# Names are private to this run (container, network, image tag); it cleans up after itself and publishes only Caddy,
# on a random 127.0.0.1 port. Needs docker, curl and the medioo-web install (pnpm, Playwright's chromium).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CADDYFILE="${BUDGET_CADDYFILE:-$ROOT/docs/infra/Caddyfile.medioo}"
SITE="medioo.eliooo.org"
# Caddy v2.11.7, the version the W1a reference was measured behind; pinned by digest (a tag can be moved).
CADDY_IMAGE="caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f"
SUFFIX="$$-$RANDOM"
SHA="budget$SUFFIX"
NET="medioo-budget-$SUFFIX"
WEB="medioo-web-budget-$SUFFIX"
CADDY="medioo-caddy-budget-$SUFFIX"
WORK="$(mktemp -d)"
BUILT=""

cleanup() {
  docker rm -f "$CADDY" "$WEB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  [ -n "$BUILT" ] && docker rmi "$BUILT" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
die() { echo "budget-behind-caddy: $*" >&2; exit 1; }

# 1. The replica. The block is extracted by brace depth from "<site> {" to its closing brace.
awk -v open="$SITE {" '
  !inside && $0 == open { inside = 1 }
  inside {
    print
    line = $0; sub(/(^|[ \t])#.*$/, "", line)
    depth += gsub(/[{]/, "{", line) - gsub(/[}]/, "}", line)
    if (depth == 0) exit
  }' "$CADDYFILE" > "$WORK/block"
[ -s "$WORK/block" ] || die "no '$SITE {' block in $CADDYFILE"
[ "$(tail -n 1 "$WORK/block")" = "}" ] || die "the $SITE block in $CADDYFILE never closes"
grep -q '^[[:space:]]*encode zstd gzip$' "$WORK/block" || die "the $SITE block has no 'encode zstd gzip': not the measured setup"
grep -q 'Strict-Transport-Security' "$WORK/block" || die "the $SITE block sets no Strict-Transport-Security: the spec could not tell a run through Caddy"
grep -q 'reverse_proxy ' "$WORK/block" || die "the $SITE block has no reverse_proxy"

sed -e "1s|^$SITE {\$|http://:8080 {|" \
    -e 's|^\([[:space:]]*reverse_proxy\) [^[:space:]]*$|\1 medioo-web:3000|' "$WORK/block" > "$WORK/Caddyfile"
# Only the address and the upstreams changed: same line count, and every differing line is one of those two kinds.
[ "$(wc -l < "$WORK/block")" = "$(wc -l < "$WORK/Caddyfile")" ] || die "the replica lost or gained lines"
changed=$(diff "$WORK/block" "$WORK/Caddyfile" | grep '^>' | grep -v -e '^> http://:8080 {$' -e '^>[[:space:]]*reverse_proxy medioo-web:3000$' || true)
[ -z "$changed" ] || die "the replica changed more than the address and upstreams: $changed"
head -n 1 "$WORK/Caddyfile" | grep -qx 'http://:8080 {' || die "the replica's address was not replaced"
! grep -q 'medscribe-ai' "$WORK/Caddyfile" || die "the replica still sends a path to Java"
if [ "${BUDGET_REPLICA_ONLY:-}" = 1 ]; then cat "$WORK/Caddyfile"; exit 0; fi
echo "budget-behind-caddy: replica of $SITE ($(wc -l < "$WORK/Caddyfile" | tr -d ' ') lines; address and upstreams changed)"

# 2. The image, built from medioo-web/Dockerfile unless one is given.
if [ -n "${BUDGET_WEB_IMAGE:-}" ]; then
  IMAGE="$BUDGET_WEB_IMAGE"
  docker image inspect "$IMAGE" >/dev/null 2>&1 || die "no image $IMAGE"
else
  IMAGE="medioo-web-budget:$SHA"
  echo "budget-behind-caddy: building medioo-web ($IMAGE)"
  docker build -q --build-arg "BUILD_SHA=$SHA" -t "$IMAGE" "$ROOT/medioo-web" >/dev/null || die "docker build medioo-web failed"
  BUILT="$IMAGE"
fi

# 3. medioo-web as production runs it (320m, no published port), and Caddy in front, on a private network.
docker network create "$NET" >/dev/null
docker run -d --name "$WEB" --network "$NET" --network-alias medioo-web --memory=320m -e "GIT_SHA=$SHA" "$IMAGE" >/dev/null \
  || die "could not start $IMAGE"
docker create --name "$CADDY" --network "$NET" -p 127.0.0.1::8080 "$CADDY_IMAGE" >/dev/null || die "could not create Caddy"
docker cp "$WORK/Caddyfile" "$CADDY:/etc/caddy/Caddyfile" >/dev/null
docker start "$CADDY" >/dev/null || die "could not start Caddy"
PORT=$(docker port "$CADDY" 8080/tcp | head -n 1 | sed 's/.*://')
[ -n "$PORT" ] || die "Caddy publishes no port"
BASE="http://127.0.0.1:$PORT"

# Ready when Caddy answers /app/healthz from this run's container (its sha), within 60 s.
ready=""
for _ in $(seq 1 60); do
  body=$(curl -sS --max-time 2 "$BASE/app/healthz" 2>/dev/null || true)
  case "$body" in *"\"sha\":\"$SHA\""*) ready=1; break ;; esac
  sleep 1
done
if [ -z "$ready" ]; then
  docker logs --tail 20 "$CADDY" >&2 || true
  die "$BASE/app/healthz never answered with sha $SHA through Caddy"
fi
echo "budget-behind-caddy: $IMAGE behind Caddy at $BASE"

# 4. The budget spec against Caddy. E2E_CATALOG_URL is set so the config starts no server of its own.
rc=0
E2E_BASE_URL="$BASE" E2E_CATALOG_URL="$BASE" E2E_GIT_SHA="$SHA" E2E_VIA_CADDY=1 \
  pnpm -C "$ROOT/medioo-web" exec playwright test -c e2e/playwright.config.ts e2e/budget.spec.ts --project phone || rc=$?

# The numbers, always (paths and byte counts only).
for f in "$ROOT"/medioo-web/test-results/budget*.json; do
  [ -e "$f" ] || { echo "budget-behind-caddy: no budget json (the spec did not reach a page)"; break; }
  node -e 'const d=require(process.argv[1]);console.log(process.argv[1].split("/").pop(), JSON.stringify({viaCaddy:d.viaCaddy, path:d.path, routeTotal:d.routeTotal, all:d.all, totals:d.totals}))' "$f"
done
exit "$rc"
