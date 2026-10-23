#!/usr/bin/env bash
#
# The enforcing DR-42 budget run (plan W1d; DR-42 item 2, "Where the enforcing run happens"): the built standalone
# medioo-web image behind a Caddy replica of docs/infra/Caddyfile.medioo, and e2e/budget.spec.ts run against that Caddy
# with E2E_VIA_CADDY=1. This is the setup the 203,969 B reference was taken in (the W1a measurement), so it is the run
# whose numbers the ceilings in medioo-web/e2e/budget-ceilings.json are about.
#
#   bash scripts/budget-behind-caddy.sh
#
# Since PO ruling P3 (2026-10-23) the block's `encode zstd gzip` is what compresses medioo-web: the image sends every
# body unencoded (next.config.ts compress: false). In the W1a reference Next still gzipped its own responses and Caddy
# passed them through; the ceilings were not moved for the change (DR-42, the P3 note).
#
# The replica is the repo's medioo.eliooo.org block, every line as written (its `encode zstd gzip` and header block
# included), with only two kinds of change, each checked below:
#   - the site address becomes `http://:8080` (plain http inside the Caddy container);
#   - every `reverse_proxy` upstream becomes medioo-web:3000. The Java upstream (medscribe-ai:8086) is pointed at the
#     web container too, so the pages it would send to Java today (every page until its flip, the probe included)
#     reach the Next build under test, as W1a's single-upstream replica did.
# The spec then requires Strict-Transport-Security on every measured document, which only that header block sets:
# a run that bypassed Caddy cannot pass.
#
# Java is the e2e stub (plan W3, Shared interfaces → the W1d image run): medioo-web/e2e/support/java-stub.mjs in a
# node:22 container named java-stub on the run's network, answering from medioo-web/fixtures/api, and the web container
# reads it as JAVA_INTERNAL_URL=http://java-stub:8086. So /app and /app/verify are measured as the sign-in pages they
# are, not as the error page a missing Java would make. Before the spec, the run asks Caddy for /app under a signed-out
# scenario and stops unless the number field comes back. The replica's Java upstream stays on medioo-web (above): a
# page Caddy sent to the stub would be the stub's 404, never the Next page whose budget this run exists to enforce.
# The browser's own /app/api calls never leave the browser (browser-api.ts answers them from the same table).
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
# The stub's Node: the same pinned node:22 image medioo-web/Dockerfile builds on.
STUB_IMAGE="node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402"
STUB_ALIAS="java-stub"
STUB_PORT=8086
SUFFIX="$$-$RANDOM"
SHA="budget$SUFFIX"
NET="medioo-budget-$SUFFIX"
WEB="medioo-web-budget-$SUFFIX"
CADDY="medioo-caddy-budget-$SUFFIX"
STUB="medioo-java-stub-budget-$SUFFIX"
WORK="$(mktemp -d)"
BUILT=""

cleanup() {
  docker rm -f "$CADDY" "$WEB" "$STUB" >/dev/null 2>&1 || true
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

# 3. medioo-web as production runs it (320m, no published port), reading stub Java, and Caddy in front, on a private
#    network.
docker network create "$NET" >/dev/null
docker run -d --name "$WEB" --network "$NET" --network-alias medioo-web --memory=320m -e "GIT_SHA=$SHA" \
  -e "JAVA_INTERNAL_URL=http://$STUB_ALIAS:$STUB_PORT" "$IMAGE" >/dev/null \
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

# 3b. Stub Java (e2e/support/java-stub.mjs), the fixtures Java's contract tests wrote, on the same network under the
#     name the web container reads. E2E_ORIGIN is the origin the browser runs at (Caddy's), as the suite's own stub has.
docker run -d --name "$STUB" --network "$NET" --network-alias "$STUB_ALIAS" \
  -v "$ROOT/medioo-web/e2e/support:/stub/support:ro" -v "$ROOT/medioo-web/fixtures/api:/stub/fixtures:ro" \
  -e STUB_HOST=0.0.0.0 -e "STUB_PORT=$STUB_PORT" -e E2E_FIXTURES=/stub/fixtures -e "E2E_ORIGIN=$BASE" \
  "$STUB_IMAGE" node /stub/support/java-stub.mjs >/dev/null || die "could not start stub Java ($STUB_IMAGE)"
# Ready when Caddy serves the sign-in page itself under a signed-out scenario: the web container read the stub's 401
# for the held place and rendered the number field. Anything else (stub Java unreachable, an error page) stops here.
signin=""
for _ in $(seq 1 30); do
  page=$(curl -sS --max-time 5 -b "e2e_scenario=signed-out" "$BASE/app" 2>/dev/null || true)
  case "$page" in *'name="whatsapp_number"'*) signin=1; break ;; esac
  sleep 1
done
if [ -z "$signin" ]; then
  docker logs --tail 20 "$STUB" >&2 || true
  die "$BASE/app never rendered the number field from stub Java at http://$STUB_ALIAS:$STUB_PORT"
fi
echo "budget-behind-caddy: stub Java at http://$STUB_ALIAS:$STUB_PORT; /app renders the sign-in page through Caddy"

# 4. The budget spec against Caddy. E2E_CATALOG_URL is set so the config starts no server of its own.
rc=0
E2E_BASE_URL="$BASE" E2E_CATALOG_URL="$BASE" E2E_GIT_SHA="$SHA" E2E_VIA_CADDY=1 \
  pnpm -C "$ROOT/medioo-web" exec playwright test -c e2e/playwright.config.ts e2e/budget.spec.ts --project phone || rc=$?

# The numbers, always (paths and byte counts only).
for f in "$ROOT"/medioo-web/test-results/budget*.json; do
  [ -e "$f" ] || { echo "budget-behind-caddy: no budget json (the spec did not reach a page)"; break; }
  node -e 'const d=require(process.argv[1]);console.log(process.argv[1].split("/").pop(), JSON.stringify({viaCaddy:d.viaCaddy, encoding:d.encoding, path:d.path, routeTotal:d.routeTotal, all:d.all, totals:d.totals}))' "$f"
done
exit "$rc"
