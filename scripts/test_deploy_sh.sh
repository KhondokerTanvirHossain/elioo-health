#!/usr/bin/env bash
#
# Tests for deploy.sh's two-container deploy (DR-40 A2, plan W1b): Java first, then medioo-web, against the fake
# `docker`, `curl`, `df` and `sleep` in scripts/deploy_test_fakes.sh. No daemon, no network, no wall-clock waits.
#
#   bash scripts/test_deploy_sh.sh                         # tests ./deploy.sh
#   DEPLOY_SH=/path/to/copy.sh bash scripts/test_deploy_sh.sh   # replays the same cases against another copy
#
# The rules under test:
# - Java is swapped and healthy BEFORE anything touches medioo-web: a web failure can never cost the Java deploy.
# - No medioo-web image for the sha (every sha before W1b; a rollback to one): Java-only, exit 0, and the newer
#   medioo-web already running is left alone. Unless WEB_IMAGE_REQUIRED=1, which CI sets because the same job
#   has just pushed that image: there a failed pull is a real failure and must not pass for a pre-W1b rollback.
# - medioo-web unhealthy after 60 s: exit 1, its last 50 log lines printed, and Java (already healthy) untouched.
# - medioo-web gets no published port and no --env-file: Node never holds Java's secrets, and nothing reaches it
#   except through Caddy on n8n_edge (no route sends it anything until W1c).
# - Both repositories are pruned before the free-space floor is read; no other repository is touched.
set -uo pipefail

DEPLOY="${DEPLOY_SH:-$(cd "$(dirname "$0")/.." && pwd)/deploy.sh}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0

fail() { echo "FAIL: $1"; FAILED=1; }

REPO=ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai
WEB=ghcr.io/khondokertanvirhossain/elioo-health/medioo-web
NEW=9e1d2c4    # the sha being deployed; on the box for neither repository yet
J_RUN=0b7c3d9  # the Java sha running now
W_RUN=0b7c3d9  # the medioo-web sha running now (same sha: they are built by the same job)

BIN="$WORK/bin"
# shellcheck source=deploy_test_fakes.sh
source "$(cd "$(dirname "$0")" && pwd)/deploy_test_fakes.sh"
write_deploy_fakes "$BIN"

# Five tags of each of our repositories, oldest first by creation time (names chosen so that name order disagrees),
# the newest of each running, plus a bddrug tag OLDER than all of them that must never be touched.
write_images() {
  cat > "$1/images.txt" <<EOF
$REPO f3a1c2e sha256:0001 2026-10-11 10:00:00 +0000 UTC
$REPO a90d4b1 sha256:0002 2026-10-12 10:00:00 +0000 UTC
$REPO d41e7a0 sha256:0003 2026-10-13 10:00:00 +0000 UTC
$REPO 5c2b9f3 sha256:0004 2026-10-14 10:00:00 +0000 UTC
$REPO $J_RUN sha256:0005 2026-10-15 10:00:00 +0000 UTC
$WEB f3a1c2e sha256:0101 2026-10-11 10:00:00 +0000 UTC
$WEB a90d4b1 sha256:0102 2026-10-12 10:00:00 +0000 UTC
$WEB d41e7a0 sha256:0103 2026-10-13 10:00:00 +0000 UTC
$WEB 5c2b9f3 sha256:0104 2026-10-14 10:00:00 +0000 UTC
$WEB $W_RUN sha256:0105 2026-10-15 10:00:00 +0000 UTC
ghcr.io/khondokertanvirhossain/bddrug-web b1 sha256:0b01 2026-09-01 10:00:00 +0000 UTC
EOF
}

# new_case <name>: a fresh fixture directory, exported as FAKE_DIR; both containers exist and are running
new_case() {
  CASE="$1"
  CASES=$((CASES+1))
  FAKE_DIR="$WORK/$1"; export FAKE_DIR
  mkdir -p "$FAKE_DIR"; : > "$FAKE_DIR/calls.log"
  write_images "$FAKE_DIR"
  printf 'c1 %s sha256:0005\nc2 %s sha256:0105\n' "$REPO:$J_RUN" "$WEB:$W_RUN" > "$FAKE_DIR/running.txt"
  printf 'medscribe-ai\nmedioo-web\n' > "$FAKE_DIR/names.txt"
}

printf 'SOME_SETTING=x\nBAYMAX_ADMIN_TOKEN=java-only-secret\n' > "$WORK/env"

# run_deploy [env assignments...]: the whole deploy.sh, executed as the box executes it; its exit code is returned
run_deploy() {
  env PATH="$BIN:$PATH" IMAGE_TAG="$NEW" ENV_FILE="$WORK/env" "$@" bash "$DEPLOY" > "$FAKE_DIR/out.log" 2>&1
}

out() { tail -8 "$FAKE_DIR/out.log" | tr '\n' '|'; }

# line numbers in calls.log (empty when absent)
first_line() { grep -nE "$1" "$FAKE_DIR/calls.log" | head -1 | cut -d: -f1; }
last_line()  { grep -nE "$1" "$FAKE_DIR/calls.log" | tail -1 | cut -d: -f1; }
count()      { grep -cE "$1" "$FAKE_DIR/calls.log"; }

JAVA_RUN_RE="^docker run .* $REPO:$NEW\$"
WEB_RUN_RE="^docker run .* $WEB:$NEW\$"
# anything that changes the web CONTAINER (pruning old web tags is not on this list: it is disk, before the pull)
WEB_TOUCH_RE="^docker (pull $WEB:|rm -f medioo-web|run .*$WEB:|exec medioo-web|stop medioo-web)"
JAVA_GONE_RE="^docker (rm|stop|kill)( -f)? medscribe-ai\$"

# arg_pair <file> <a> <b>: the recorded `docker run` has argument <a> immediately followed by argument <b>
arg_pair() { awk -v a="$2" -v b="$3" 'prev == a && $0 == b {f = 1} {prev = $0} END {exit !f}' "$1"; }

# 1. the function itself: deploy_web <tag> exists, and sourced alone deploys medioo-web and exits 0
new_case function
PATH="$BIN:$PATH" DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" && deploy_web "$2"' _ "$DEPLOY" "$NEW" \
  > "$FAKE_DIR/out.log" 2>&1 || fail "$CASE: deploy_web $NEW failed: $(out)"
[ -n "$(first_line "$WEB_RUN_RE")" ] || fail "$CASE: deploy_web never ran $WEB:$NEW: $(out)"

# 2. (a) Java first: the Java rm and run, and its health, all come before anything touches medioo-web
new_case java-first
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
JAVA_RM=$(first_line "$JAVA_GONE_RE"); JAVA_RUN=$(first_line "$JAVA_RUN_RE")
JAVA_HEALTH=$(last_line '^curl .*actuator/health'); WEB_FIRST=$(first_line "$WEB_TOUCH_RE")
WEB_RUN=$(first_line "$WEB_RUN_RE")
if [ -z "$JAVA_RM" ] || [ -z "$JAVA_RUN" ] || [ -z "$JAVA_HEALTH" ] || [ -z "$WEB_RUN" ]; then
  fail "$CASE: missing a step (java rm '$JAVA_RM', java run '$JAVA_RUN', java health '$JAVA_HEALTH', web run '$WEB_RUN'): $(out)"
else
  [ "$JAVA_RM" -lt "$JAVA_RUN" ] || fail "$CASE: Java run (line $JAVA_RUN) before its rm (line $JAVA_RM)"
  [ "$JAVA_RUN" -lt "$JAVA_HEALTH" ] || fail "$CASE: Java health (line $JAVA_HEALTH) before its run (line $JAVA_RUN)"
  [ "$JAVA_HEALTH" -lt "$WEB_FIRST" ] \
    || fail "$CASE: medioo-web touched (line $WEB_FIRST: $(sed -n "${WEB_FIRST}p" "$FAKE_DIR/calls.log")) before Java was healthy (line $JAVA_HEALTH)"
fi
# the old web container is replaced, after the new image is pulled and before the new one runs
WEB_PULL=$(first_line "^docker pull $WEB:$NEW\$"); WEB_RM=$(first_line '^docker rm -f medioo-web$')
if [ -z "$WEB_PULL" ] || [ -z "$WEB_RM" ]; then
  fail "$CASE: no web pull ('$WEB_PULL') or no removal of the old medioo-web ('$WEB_RM')"
elif [ -n "$WEB_RUN" ]; then
  { [ "$WEB_PULL" -lt "$WEB_RM" ] && [ "$WEB_RM" -lt "$WEB_RUN" ]; } \
    || fail "$CASE: expected web pull ($WEB_PULL) < rm ($WEB_RM) < run ($WEB_RUN)"
fi
[ "$(count "$JAVA_GONE_RE")" = 1 ] || fail "$CASE: medscribe-ai removed $(count "$JAVA_GONE_RE") times, expected once"
grep -q "medioo-web healthy" "$FAKE_DIR/out.log" || fail "$CASE: no 'medioo-web healthy' line: $(out)"

# 3. (d) the medioo-web run line: no published port, no env file, no mounts; exactly the two env vars it needs
new_case web-args
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
A="$FAKE_DIR/run.medioo-web.args"
if [ -f "$A" ]; then
  grep -qxE -- '-p|--publish|-P|--publish-all' "$A" && fail "$CASE: medioo-web publishes a port: $(tr '\n' '|' < "$A")"
  grep -qE -- '^(-p|--publish)=' "$A" && fail "$CASE: medioo-web publishes a port: $(tr '\n' '|' < "$A")"
  grep -qE -- '^--env-file' "$A" && fail "$CASE: medioo-web gets an --env-file (Java's secrets): $(tr '\n' '|' < "$A")"
  grep -qxE -- '-v|--volume|--mount' "$A" && fail "$CASE: medioo-web mounts something: $(tr '\n' '|' < "$A")"
  grep -q 'java-only-secret' "$A" && fail "$CASE: a value from Java's env file reached medioo-web"
  ENVS=$(awk 'prev == "-e" {print} {prev = $0}' "$A" | LC_ALL=C sort | tr '\n' ' ')
  [ "$ENVS" = "GIT_SHA=$NEW JAVA_INTERNAL_URL=http://medscribe-ai:8086 " ] \
    || fail "$CASE: medioo-web env is [$ENVS], expected exactly GIT_SHA and JAVA_INTERNAL_URL"
  grep -qE -- '^--env=|^--env$' "$A" && fail "$CASE: an env var passed with --env, outside the -e list checked above"
  arg_pair "$A" --name medioo-web || fail "$CASE: no '--name medioo-web'"
  arg_pair "$A" --network n8n_edge || fail "$CASE: no '--network n8n_edge'"
  arg_pair "$A" --restart unless-stopped || fail "$CASE: no '--restart unless-stopped'"
  arg_pair "$A" --log-opt max-size=10m || fail "$CASE: no '--log-opt max-size=10m'"
  arg_pair "$A" --log-opt max-file=3 || fail "$CASE: no '--log-opt max-file=3'"
  grep -qx -- '--memory=320m' "$A" || fail "$CASE: no '--memory=320m'"
  grep -qx '' "$A" && fail "$CASE: docker run got an empty argument: $(tr '\n' '|' < "$A")"
  [ "$(tail -1 "$A")" = "$WEB:$NEW" ] || fail "$CASE: $WEB:$NEW is not docker run's last argument"
else
  fail "$CASE: medioo-web was never run: $(out)"
fi
# the health probe is the healthz fetch inside the container, and it checks it is THIS sha answering
grep -E '^docker exec medioo-web node -e ' "$FAKE_DIR/calls.log" | head -1 | grep -q '127.0.0.1:3000/app/healthz' \
  || fail "$CASE: the web health probe is not the in-container /app/healthz fetch: $(grep '^docker exec' "$FAKE_DIR/calls.log" | head -1)"
grep -E '^docker exec medioo-web ' "$FAKE_DIR/calls.log" | head -1 | grep -q 'GIT_SHA' \
  || fail "$CASE: the web health probe does not check that the new sha answers"

# 4. no edge network: medioo-web runs without --network and with no empty argument where it would have been
new_case no-network
run_deploy || fail "$CASE: deploy.sh failed: $(out)"
A="$FAKE_DIR/run.medioo-web.args"
if [ -f "$A" ]; then
  grep -qx -- '--network' "$A" && fail "$CASE: --network passed with no edge network"
  grep -qx '' "$A" && fail "$CASE: docker run got an empty argument: $(tr '\n' '|' < "$A")"
else
  fail "$CASE: medioo-web was never run: $(out)"
fi

# 5. (b) no web image for this sha (every sha before W1b): exit 0, Java deployed, the log line, the newer
#    medioo-web already running is left alone (not removed, not replaced)
new_case no-web-image
run_deploy FAKE_PULL_FAIL="$WEB:$NEW" || fail "$CASE: deploy.sh failed with no web image: $(out)"
[ -n "$(first_line "$JAVA_RUN_RE")" ] || fail "$CASE: Java was not deployed"
grep -qF "web image $NEW not found; Java-only deploy" "$FAKE_DIR/out.log" \
  || fail "$CASE: no 'web image $NEW not found; Java-only deploy' line: $(out)"
grep -qE '^docker (rm|stop|kill)( -f)? medioo-web$' "$FAKE_DIR/calls.log" \
  && fail "$CASE: the running medioo-web was removed although there was nothing to replace it with"
[ -z "$(first_line "^docker run .*$WEB:")" ] || fail "$CASE: a medioo-web container was started"

# 6. the same missing image when CI says the image must exist (it just pushed it): a real failure, exit 1,
#    and Java, already healthy, stays
new_case web-image-required
if run_deploy FAKE_PULL_FAIL="$WEB:$NEW" WEB_IMAGE_REQUIRED=1; then
  fail "$CASE: exited 0 with WEB_IMAGE_REQUIRED=1 and no web image: $(out)"
fi
[ -n "$(first_line "$JAVA_RUN_RE")" ] || fail "$CASE: Java was not deployed"
JAVA_RUN=$(first_line "$JAVA_RUN_RE"); JAVA_RM_LAST=$(last_line "$JAVA_GONE_RE")
[ -n "$JAVA_RM_LAST" ] && [ -n "$JAVA_RUN" ] && [ "$JAVA_RM_LAST" -gt "$JAVA_RUN" ] && fail "$CASE: Java removed after its run"
grep -qE '^docker (rm|stop|kill)( -f)? medioo-web$' "$FAKE_DIR/calls.log" && fail "$CASE: the running medioo-web was removed"
grep -q 'ERROR' "$FAKE_DIR/out.log" || fail "$CASE: no ERROR line: $(out)"

# 7. (c) medioo-web never healthy: exit 1 after 60 s of tries, its last 50 log lines printed, and Java (healthy
#    before the web step began) neither removed nor stopped after its run
new_case web-unhealthy
if run_deploy FAKE_WEB_HEALTH=fail; then fail "$CASE: exited 0 with medioo-web unhealthy: $(out)"; fi
JAVA_RUN=$(first_line "$JAVA_RUN_RE"); JAVA_RM_LAST=$(last_line "$JAVA_GONE_RE")
[ -n "$JAVA_RUN" ] || fail "$CASE: Java was not deployed"
[ -n "$JAVA_RM_LAST" ] && [ -n "$JAVA_RUN" ] && [ "$JAVA_RM_LAST" -gt "$JAVA_RUN" ] \
  && fail "$CASE: Java removed (line $JAVA_RM_LAST) after its run (line $JAVA_RUN)"
[ "$(count '^docker exec medioo-web ')" = 30 ] \
  || fail "$CASE: $(count '^docker exec medioo-web ') health tries, expected 30 (60 s at 2 s)"
grep -q '^docker logs --tail 50 medioo-web$' "$FAKE_DIR/calls.log" || fail "$CASE: the last 50 log lines were not printed"
grep -q 'fake container log line' "$FAKE_DIR/out.log" || fail "$CASE: the container's log lines did not reach the output"
grep -q 'medioo-web did not become healthy in 60s' "$FAKE_DIR/out.log" || fail "$CASE: no timeout line: $(out)"

# 8. medioo-web slow to start (healthy on the third try): waits, exits 0
new_case web-slow
run_deploy FAKE_WEB_HEALTH=2 || fail "$CASE: deploy.sh failed although medioo-web became healthy: $(out)"
[ "$(count '^docker exec medioo-web ')" = 3 ] || fail "$CASE: $(count '^docker exec medioo-web ') health tries, expected 3"

# 9. Java never healthy: exit 1 and medioo-web is not touched at all (no pull, no rm, no run)
new_case java-unhealthy
if run_deploy FAKE_CURL_FAIL=1; then fail "$CASE: exited 0 with Java unhealthy: $(out)"; fi
[ -z "$(first_line "$WEB_TOUCH_RE")" ] \
  || fail "$CASE: medioo-web touched although Java was not healthy: $(sed -n "$(first_line "$WEB_TOUCH_RE")p" "$FAKE_DIR/calls.log")"

# 10. both repositories pruned to the newest 3, never the running image, never bddrug's, all before the floor is
#     read and before the Java pull
new_case prune-both
run_deploy || fail "$CASE: deploy.sh failed: $(out)"
GOT=$(awk '$2 == "rmi" {print $3}' "$FAKE_DIR/calls.log" | LC_ALL=C sort | tr '\n' ' ')
WANT=$(printf '%s\n' "$REPO:a90d4b1" "$REPO:f3a1c2e" "$WEB:a90d4b1" "$WEB:f3a1c2e" | LC_ALL=C sort | tr '\n' ' ')
[ "$GOT" = "$WANT" ] || fail "$CASE: removed [$GOT], expected [$WANT]"
LAST_RMI=$(last_line '^docker rmi '); DF=$(first_line '^df '); JAVA_PULL=$(first_line "^docker pull $REPO:")
if [ -n "$LAST_RMI" ] && [ -n "$DF" ] && [ -n "$JAVA_PULL" ]; then
  { [ "$LAST_RMI" -lt "$DF" ] && [ "$DF" -lt "$JAVA_PULL" ]; } \
    || fail "$CASE: expected every rmi ($LAST_RMI) < the free-space check ($DF) < the Java pull ($JAVA_PULL)"
else
  fail "$CASE: missing rmi ('$LAST_RMI'), df ('$DF') or Java pull ('$JAVA_PULL')"
fi

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-sh: $CASES cases passed"
