#!/usr/bin/env bash
#
# The Caddy replica that scripts/budget-behind-caddy.sh builds for the DR-42 enforcing run (plan W1d), tested both ways
# without Docker (BUDGET_REPLICA_ONLY=1):
#   - from the repo's docs/infra/Caddyfile.medioo, the replica is the medioo.eliooo.org block with only the address and
#     the reverse_proxy upstreams changed: every other line identical, in order, and nothing sent to Java;
#   - a source block without `encode zstd gzip`, without Strict-Transport-Security, without the site, or that never
#     closes, is refused with a message naming why, so the run can never measure a setup that is not production's.
#
#   bash scripts/test_budget_replica.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/budget-behind-caddy.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
fail() { echo "FAIL: $1"; FAILED=1; }
replica() { BUDGET_REPLICA_ONLY=1 BUDGET_CADDYFILE="$1" bash "$SCRIPT" 2>&1; }

# 1. The real file.
out=$(replica "$ROOT/docs/infra/Caddyfile.medioo") || fail "the repo's Caddyfile.medioo was refused: $out"
printf '%s\n' "$out" > "$WORK/replica"
awk '!inside && $0 == "medioo.eliooo.org {" { inside = 1 } inside { print; if ($0 == "}") exit }' \
  "$ROOT/docs/infra/Caddyfile.medioo" > "$WORK/block"
[ -s "$WORK/block" ] || fail "test setup: no medioo.eliooo.org block found in the repo file"
[ "$(head -n 1 "$WORK/replica")" = "http://:8080 {" ] || fail "replica address is [$(head -n 1 "$WORK/replica")]"
grep -q 'medscribe-ai' "$WORK/replica" && fail "the replica still sends a path to Java"
# Every line that is neither the address nor a reverse_proxy is the source's, in the same order.
diff <(tail -n +2 "$WORK/block" | grep -v 'reverse_proxy') <(tail -n +2 "$WORK/replica" | grep -v 'reverse_proxy') >/dev/null \
  || fail "the replica changed lines other than the address and upstreams"
n_src=$(grep -c 'reverse_proxy' "$WORK/block"); n_rep=$(grep -c 'reverse_proxy medioo-web:3000$' "$WORK/replica")
[ "$n_src" -gt 0 ] && [ "$n_src" = "$n_rep" ] || fail "upstreams: $n_src in the source, $n_rep pointed at medioo-web:3000"
grep -qx $'\tencode zstd gzip' "$WORK/replica" || fail "the replica lost encode zstd gzip"
grep -q 'Strict-Transport-Security "max-age=31536000"' "$WORK/replica" || fail "the replica lost the HSTS header"

# 2. Sources that are not production's setup, each refused for its own reason.
good=$'medioo.eliooo.org {\n\tencode zstd gzip\n\theader {\n\t\tStrict-Transport-Security "max-age=31536000"\n\t}\n\thandle {\n\t\treverse_proxy medscribe-ai:8086\n\t}\n}\n'
printf '%s' "$good" > "$WORK/good"
out=$(replica "$WORK/good") || fail "the minimal good fixture was refused: $out"
refused() { # <name> <expected message part> <fixture text>
  printf '%s' "$3" > "$WORK/$1"
  local o; o=$(replica "$WORK/$1") && { fail "$1: accepted"; return; }
  case "$o" in *"$2"*) ;; *) fail "$1: refused for another reason: $o" ;; esac
}
refused no-encode "has no 'encode zstd gzip'" "${good/$'\tencode zstd gzip\n'/}"
refused no-hsts "sets no Strict-Transport-Security" "${good/Strict-Transport-Security/X-Other}"
refused no-site "no 'medioo.eliooo.org {' block" "${good/medioo.eliooo.org/example.org}"
close=$'}\n'
refused unclosed "never closes" "${good%"$close"}"
refused no-proxy "has no reverse_proxy" "${good/reverse_proxy medscribe-ai:8086/respond 200}"

[ "$FAILED" = 0 ] && echo "test_budget_replica: all cases passed"
exit "$FAILED"
