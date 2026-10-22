#!/usr/bin/env bash
#
# Does each row of the smoke table reach the upstream its class names? (plan Wave 3, Task F0)
#
#   scripts/caddy-route-probe.sh docs/infra/Caddyfile.medioo scripts/smoke-https.sh
#
# The routing is derived from the same table the box run checks (scripts/smoke-https.sh), not from a second list:
# - the medioo.eliooo.org site block is copied from the Caddyfile, every line as written, with three kinds of change:
#   the site address becomes http://127.0.0.1:<port> (plain http, so Caddy's automatic HTTPS stays off and no row is
#   answered by its 308 to https); `reverse_proxy medscribe-ai:8086` becomes `respond "java" 299`; and
#   `reverse_proxy medioo-web:3000` becomes `respond "next" 298`. Any other upstream is refused before Caddy starts;
# - the result runs in a throwaway Caddy of the box's version, published on a random 127.0.0.1 port;
# - every row of the smoke table is sent with its method and path, PROBE rows included ({old} rows are the old
#   hostname's redirect block and are skipped). A row of class `next` or `static` must get 298 (medioo-web), every
#   other row 299 (Java). A table with no rows fails: a probe that asks nothing must not pass.
#
# Prints one PASS or FAIL line per row (paths and codes only) and exits 0 only when every row passes; 2 on usage.
# Writes nothing outside its temp dir. Needs docker and curl. Tested both ways by scripts/test_caddy_route_probe.sh;
# run by hand at Task F Step 4 of every flip, and by CI through the test.
set -uo pipefail

CADDYFILE="${1:-}"
SMOKE="${2:-}"
if [ -z "$CADDYFILE" ] || [ -z "$SMOKE" ]; then
  echo "usage: caddy-route-probe.sh <Caddyfile.medioo> <smoke-https.sh>" >&2
  exit 2
fi
SITE="medioo.eliooo.org"
JAVA_UPSTREAM="reverse_proxy medscribe-ai:8086"
NEXT_UPSTREAM="reverse_proxy medioo-web:3000"
# The box's Caddy (`docker exec n8n-caddy-1 caddy version` read v2.11.4 on 2026-10-22), pinned by its index digest so
# the same version runs on an arm64 laptop and the amd64 CI runner. Move it when the box's Caddy moves. There is a
# second Caddy pin in the repo, scripts/budget-behind-caddy.sh (the W1d replica of DR-42's enforcing run), which still
# runs 2.11.7, the version the W1a reference was measured behind; the two disagree until a follow-up aligns them or
# records why they differ, so check both when the box moves.
CADDY_IMAGE="caddy:2.11.4-alpine@sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b"
PORT=8080
NAME="medioo-route-probe-$$-$RANDOM"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
die() { echo "caddy-route-probe: $*"; exit 1; }

[ -r "$CADDYFILE" ] || die "cannot read $CADDYFILE"
[ -r "$SMOKE" ] || die "cannot read $SMOKE"
command -v docker >/dev/null 2>&1 || die "docker is required"
command -v curl >/dev/null 2>&1 || die "curl is required"

# 1. The rows: the TABLE='...' literal of the smoke script, one "path|status|class|check" per line.
awk '
  !inside && /^TABLE=\047/ { inside = 1; sub(/^TABLE=\047/, "") }
  inside {
    line = $0; last = (line ~ /\047$/)
    if (last) sub(/\047$/, "", line)
    if (line != "") print line
    if (last) exit
  }' "$SMOKE" | grep -v '^{old}' > "$WORK/rows" || true
[ -s "$WORK/rows" ] || die "read no rows from the TABLE in $SMOKE; a probe that asks nothing cannot pass"

# 2. The site block, extracted by brace depth from "<site> {" to its closing brace (as budget-behind-caddy.sh does).
awk -v open="$SITE {" '
  !inside && $0 == open { inside = 1 }
  inside {
    print
    line = $0; sub(/(^|[ \t])#.*$/, "", line)
    depth += gsub(/[{]/, "{", line) - gsub(/[}]/, "}", line)
    if (depth == 0) exit
  }' "$CADDYFILE" > "$WORK/block"
[ -s "$WORK/block" ] || die "no $SITE block in $CADDYFILE"
[ "$(tail -n 1 "$WORK/block")" = "}" ] || die "the $SITE block in $CADDYFILE never closes"
unknown=$(grep -n 'reverse_proxy' "$WORK/block" | grep -vE "^[0-9]+:[[:space:]]*($JAVA_UPSTREAM|$NEXT_UPSTREAM)\$" || true)
[ -z "$unknown" ] || die "unknown upstream in the $SITE block (only medscribe-ai:8086 and medioo-web:3000 are replaced): $unknown"
grep -q 'reverse_proxy' "$WORK/block" || die "the $SITE block has no reverse_proxy"

sed -e "1s|^$SITE {\$|http://127.0.0.1:$PORT {|" \
    -e "s|^\([[:space:]]*\)$JAVA_UPSTREAM\$|\1respond \"java\" 299|" \
    -e "s|^\([[:space:]]*\)$NEXT_UPSTREAM\$|\1respond \"next\" 298|" "$WORK/block" > "$WORK/Caddyfile"
head -n 1 "$WORK/Caddyfile" | grep -qx "http://127.0.0.1:$PORT {" || die "the site address was not replaced"
! grep -q 'reverse_proxy' "$WORK/Caddyfile" || die "an upstream was not replaced"
[ "$(wc -l < "$WORK/block")" = "$(wc -l < "$WORK/Caddyfile")" ] || die "the probe file lost or gained lines"

# 3. Caddy, on a random 127.0.0.1 port, ready when it answers at all.
docker create --name "$NAME" -p "127.0.0.1::$PORT" "$CADDY_IMAGE" >/dev/null 2>&1 \
  || { docker pull -q "$CADDY_IMAGE" >/dev/null 2>&1 && docker create --name "$NAME" -p "127.0.0.1::$PORT" "$CADDY_IMAGE" >/dev/null; } \
  || die "could not create a Caddy container from $CADDY_IMAGE"
docker cp "$WORK/Caddyfile" "$NAME:/etc/caddy/Caddyfile" >/dev/null || die "could not copy the probe file into Caddy"
docker start "$NAME" >/dev/null || die "could not start Caddy"
HOST_PORT=$(docker port "$NAME" "$PORT/tcp" 2>/dev/null | head -n 1 | sed 's/.*://')
[ -n "$HOST_PORT" ] || { docker logs --tail 20 "$NAME" 2>&1; die "Caddy publishes no port (did the file validate?)"; }
BASE="http://127.0.0.1:$HOST_PORT"
ready=""
for _ in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$BASE/" 2>/dev/null || true)
  [ -n "$code" ] && [ "$code" != 000 ] && { ready=1; break; }
  sleep 1
done
[ -n "$ready" ] || { docker logs --tail 20 "$NAME" 2>&1; die "Caddy never answered at $BASE"; }

# 4. Every row, with its method: "PROBE <METHOD> /path", "<METHOD> /path" or "/path" (GET).
upstream() { case "$1" in 298) echo next ;; 299) echo java ;; *) echo other ;; esac; }
rows=0
failed=0
while IFS='|' read -r spec _status class _check; do
  [ -n "$spec" ] || continue
  rows=$((rows + 1))
  target="${spec#PROBE }"
  method=GET
  if [[ "$target" =~ ^([A-Z]+)\ (.+)$ ]]; then method="${BASH_REMATCH[1]}"; target="${BASH_REMATCH[2]}"; fi
  case "$class" in next | static) want=298 ;; *) want=299 ;; esac
  if [ "$method" = HEAD ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --head "$BASE$target" 2>/dev/null || true)
  else
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X "$method" "$BASE$target" 2>/dev/null || true)
  fi
  code="${code:-000}"
  if [ "$code" = "$want" ]; then
    echo "PASS $spec $code ($(upstream "$code"))"
  else
    echo "FAIL $spec got $code ($(upstream "$code")), expected $want ($(upstream "$want"))"
    failed=$((failed + 1))
  fi
done < "$WORK/rows"

echo "caddy-route-probe: $rows rows, $failed failed ($CADDY_IMAGE)"
[ "$failed" = 0 ]
