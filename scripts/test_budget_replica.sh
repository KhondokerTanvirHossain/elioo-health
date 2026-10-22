#!/usr/bin/env bash
#
# The Caddy replica that scripts/budget-behind-caddy.sh builds for the DR-42 enforcing run (plan W1d), tested both ways
# without Docker (BUDGET_REPLICA_ONLY=1):
#   - from the repo's docs/infra/Caddyfile.medioo, the replica is the medioo.eliooo.org block with only the address and
#     the reverse_proxy upstreams changed: every other line identical, in order, and nothing sent to Java;
#   - a source block without `encode zstd gzip`, without Strict-Transport-Security, without the site, that never
#     closes, or whose Java upstream the rewrite cannot replace, is refused with a message naming why, so the run can never measure a setup that is not production's.
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
# An upstream the rewrite cannot replace (a block-form reverse_proxy, or two upstreams on one line) keeps Java in the
# replica; only the "still sends a path to Java" guard stands between that and a run measuring Java's pages.
java_block=$'reverse_proxy medscribe-ai:8086 {\n\t\t\tflush_interval -1\n\t\t}'
refused java-block "still sends a path to Java" "${good/reverse_proxy medscribe-ai:8086/$java_block}"
refused java-two-upstreams "still sends a path to Java" "${good/reverse_proxy medscribe-ai:8086/reverse_proxy medscribe-ai:8086 medscribe-ai:8087}"

# 3. The run's containers (plan W3: stub Java for the image run), with docker, curl and pnpm faked on PATH: every
#    docker call is recorded, curl answers healthz with the run's own sha and /app with what $FAKE_SIGNIN says, pnpm
#    passes. The real script must start stub Java on the network as java-stub, from the repo's e2e support and
#    fixtures, and give the web container JAVA_INTERNAL_URL pointing at it; and it must stop when /app does not render
#    the sign-in page through Caddy. A copy of the script without the web container's JAVA_INTERNAL_URL is shown to
#    fail the same checks, so they are not green for another reason.
FAKE="$WORK/fake"; mkdir -p "$FAKE"
cat > "$FAKE/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_LOG"
case "$1" in
  port) echo "127.0.0.1:43210" ;;
  run|create) echo "fake-container-id" ;;
esac
exit 0
EOF
cat > "$FAKE/curl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  */app/healthz*) sha=$(grep -o 'GIT_SHA=[^ ]*' "$FAKE_LOG" | head -n 1 | cut -d= -f2); printf '{"sha":"%s"}' "$sha" ;;
  */app*) printf '%s' "$FAKE_SIGNIN" ;;
esac
exit 0
EOF
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> "$FAKE_LOG.pnpm"\nexit 0\n' > "$FAKE/pnpm"
printf '#!/usr/bin/env bash\nexit 0\n' > "$FAKE/sleep"
chmod +x "$FAKE"/*
run_faked() { # <script> <signin page> -> output; the docker calls in $WORK/docker.log
  : > "$WORK/docker.log"; : > "$WORK/docker.log.pnpm"
  PATH="$FAKE:$PATH" FAKE_LOG="$WORK/docker.log" FAKE_SIGNIN="$2" BUDGET_WEB_IMAGE=fake/medioo-web:1 \
    BUDGET_CADDYFILE="$ROOT/docs/infra/Caddyfile.medioo" bash "$1" 2>&1
}
SIGNIN_PAGE='<input name="whatsapp_number">'
# The checks on the recorded docker calls; prints each problem.
stub_wiring_problems() { # <docker.log> <repo root the script ran from>
  local log="$1" root="$2" stub web
  stub=$(grep '^run .*--network-alias java-stub ' "$log" || true)
  web=$(grep '^run .*--network-alias medioo-web ' "$log" || true)
  [ -n "$stub" ] || echo "no container started as java-stub on the network"
  case "$stub" in *"-v $root/medioo-web/e2e/support:/stub/support:ro"*) ;; *) echo "stub Java does not mount e2e/support" ;; esac
  case "$stub" in *"-v $root/medioo-web/fixtures/api:/stub/fixtures:ro"*) ;; *) echo "stub Java does not mount fixtures/api" ;; esac
  for env in "STUB_HOST=0.0.0.0" "STUB_PORT=8086" "E2E_FIXTURES=/stub/fixtures" "E2E_ORIGIN=http://127.0.0.1:43210"; do
    case "$stub" in *"-e $env "*) ;; *) echo "stub Java lacks $env" ;; esac
  done
  case "$stub" in *" node /stub/support/java-stub.mjs") ;; *) echo "stub Java does not run java-stub.mjs" ;; esac
  case "$web" in *"-e JAVA_INTERNAL_URL=http://java-stub:8086 "*) ;; *) echo "the web container does not read stub Java" ;; esac
}
out=$(run_faked "$SCRIPT" "$SIGNIN_PAGE") || fail "the run with stub Java answering failed: $out"
problems=$(stub_wiring_problems "$WORK/docker.log" "$ROOT")
[ -z "$problems" ] || fail "stub wiring: $problems"
grep -q '^rm -f .*medioo-java-stub-budget-' "$WORK/docker.log" || fail "the run does not remove its stub container"
# An error page through Caddy (stub Java unreachable) stops the run before the spec, naming why.
out=$(run_faked "$SCRIPT" '<h1>Internal Server Error</h1>') && fail "a run whose /app is an error page was accepted"
case "$out" in *"never rendered the number field from stub Java"*) ;; *) fail "an error-page run failed for another reason: $out" ;; esac
grep -q 'playwright' "$WORK/docker.log.pnpm" && fail "the spec ran although /app was an error page"
# The checks fail on a script that does not hand the web container JAVA_INTERNAL_URL.
mkdir -p "$WORK/broken/scripts"
sed 's|-e "JAVA_INTERNAL_URL=http://$STUB_ALIAS:$STUB_PORT" ||' "$SCRIPT" > "$WORK/broken/scripts/budget-behind-caddy.sh"
cmp -s "$SCRIPT" "$WORK/broken/scripts/budget-behind-caddy.sh" && fail "test setup: the broken copy is the script"
run_faked "$WORK/broken/scripts/budget-behind-caddy.sh" "$SIGNIN_PAGE" >/dev/null 2>&1
problems=$(stub_wiring_problems "$WORK/docker.log" "$WORK/broken")
case "$problems" in *"the web container does not read stub Java"*) ;; *) fail "a script without JAVA_INTERNAL_URL passed the wiring checks: [$problems]" ;; esac

[ "$FAILED" = 0 ] && echo "test_budget_replica: all cases passed"
exit "$FAILED"
