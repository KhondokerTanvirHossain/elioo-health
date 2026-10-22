#!/usr/bin/env bash
#
# Tests for deploy.sh's two-container deploy (DR-40 A2, plan W1b): Java first, then medioo-web, against the fake
# `docker`, `curl`, `df` and `sleep` in scripts/deploy_test_fakes.sh. No daemon, no network, no wall-clock waits
# (except the two timeout cases, a few seconds each). The real image and daemon: scripts/test_deploy_web_docker.sh.
#
#   bash scripts/test_deploy_sh.sh                         # tests ./deploy.sh
#   DEPLOY_SH=/path/to/copy.sh bash scripts/test_deploy_sh.sh   # replays the same cases against another copy
#
# The rules under test:
# - Java is swapped and healthy BEFORE anything touches medioo-web: a web failure can never cost the Java deploy.
#   After Java's run, every docker call is read-only or about medioo-web alone (derived from calls.log, not a verb list).
# - The web health probe RUNS (the fake exec executes it against a stub /app/healthz): it passes only for service
#   medioo-web whose sha (the container's GIT_SHA) and build (the image's baked sha) both equal the deployed tag.
#   Wrong sha, wrong image, wrong service, a 500, a non-JSON body: 30 tries, exit 1. A hung exec or a hung server
#   cannot stall the wait past its deadline.
# - The web image pull: GHCR's missing-tag answer (every sha before W1b) deploys Java only, exit 0, the running
#   medioo-web untouched; unless WEB_IMAGE_REQUIRED=1 (CI, which has just pushed it). Any other pull error (denied,
#   no space, network) is a fault: exit 1 whatever WEB_IMAGE_REQUIRED says. A tag already on disk is used as is.
# - medioo-web unhealthy, or its run failing: exit 1, its last 50 log lines printed, the previous medioo-web put back.
# - medioo-web healthy but serving the component catalog (/app/catalog-test not a 404, or a 404 that renders a catalog
#   state: an image built with MEDIOO_CATALOG=1): the catalog probe RUNS inside the container once the health probe
#   passed, and refuses it the same way, at once (plan W2c).
# - No edge network: exit 1 before the running medioo-web is touched (it would be unreachable both ways).
# - medioo-web gets no published port and no --env-file: Node never holds Java's secrets.
# - Both repositories are pruned before the free-space floor is read; the tag being deployed is never pruned, in
#   either repository (a rollback must not delete the known-good web image); no other repository is touched.
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
OLDEST=f3a1c2e # the oldest tag of both repositories: a rollback target that the prune would otherwise remove

BIN="$WORK/bin"
# shellcheck source=deploy_test_fakes.sh
source "$(cd "$(dirname "$0")" && pwd)/deploy_test_fakes.sh"
write_deploy_fakes "$BIN"

# Five tags of each of our repositories, oldest first by creation time (names chosen so that name order disagrees),
# the newest of each running, plus a bddrug tag OLDER than all of them that must never be touched.
write_images() {
  cat > "$1/images.txt" <<EOF
$REPO $OLDEST sha256:0001 2026-10-11 10:00:00 +0000 UTC
$REPO a90d4b1 sha256:0002 2026-10-12 10:00:00 +0000 UTC
$REPO d41e7a0 sha256:0003 2026-10-13 10:00:00 +0000 UTC
$REPO 5c2b9f3 sha256:0004 2026-10-14 10:00:00 +0000 UTC
$REPO $J_RUN sha256:0005 2026-10-15 10:00:00 +0000 UTC
$WEB $OLDEST sha256:0101 2026-10-11 10:00:00 +0000 UTC
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
  printf 'medscribe-ai %s\nmedioo-web %s\n' "$REPO:$J_RUN" "$WEB:$W_RUN" > "$FAKE_DIR/names.txt"
  printf 'JAVA_INTERNAL_URL=http://medscribe-ai:8086\nGIT_SHA=%s\n' "$W_RUN" > "$FAKE_DIR/container.medioo-web.env"
}

printf 'SOME_SETTING=x\nBAYMAX_ADMIN_TOKEN=java-only-secret\n' > "$WORK/env"

# run_deploy [env assignments...]: the whole deploy.sh, executed as the box executes it; its exit code is returned.
# IMAGE_TAG defaults to $NEW; a case can pass its own. Bounded by RUN_LIMIT seconds (default 300), so a replay
# against a copy whose waits never end fails on the clock instead of hanging the suite.
TIMEOUT=$(PATH="$BIN:$PATH" command -v timeout)
run_deploy() {
  env PATH="$BIN:$PATH" IMAGE_TAG="$NEW" ENV_FILE="$WORK/env" ROUTES_FLOOR_FILE="$FAKE_DIR/routes-floor" "$@" \
    "$TIMEOUT" "${RUN_LIMIT:-300}" bash "$DEPLOY" > "$FAKE_DIR/out.log" 2>&1
}

out() { tail -8 "$FAKE_DIR/out.log" | tr '\n' '|'; }

# line numbers in calls.log (empty when absent)
first_line() { grep -nE "$1" "$FAKE_DIR/calls.log" | head -1 | cut -d: -f1; }
last_line()  { grep -nE "$1" "$FAKE_DIR/calls.log" | tail -1 | cut -d: -f1; }
count()      { grep -cE "$1" "$FAKE_DIR/calls.log"; }
# count_between <from-line> <to-line> <re>: matches strictly between the two lines (to empty = end of file)
count_between() { awk -v a="$1" -v b="${2:-999999}" -v re="$3" 'NR > a && NR < b && $0 ~ re {n++} END {print n + 0}' "$FAKE_DIR/calls.log"; }

JAVA_RUN_RE="^docker run .* $REPO:$NEW\$"
WEB_RUN_RE="^docker run .* $WEB:$NEW\$"
WEB_ANY_RUN_RE="^docker run .* $WEB:[0-9a-f]+\$"
WEB_RM_RE='^docker rm( -f)? medioo-web$'
# the two in-container probes, told apart by the path each fetches (deploy.sh's WEB_PROBE and WEB_CATALOG_PROBE)
EXEC_RE='^docker exec medioo-web node -e .*/app/healthz'
CATALOG_EXEC_RE='^docker exec medioo-web node -e .*/app/catalog-test'
JAVA_GONE_RE="^docker (rm|stop|kill)( -f)? medscribe-ai\$"
READ_ONLY_RE='^docker (ps|images|inspect|logs|(image|network|container) inspect)( |$)'

# arg_pair <file> <a> <b>: the recorded `docker run` has argument <a> immediately followed by argument <b>
arg_pair() { awk -v a="$2" -v b="$3" 'prev == a && $0 == b {f = 1} {prev = $0} END {exit !f}' "$1"; }

# java_untouched_after_run [<java-run-re>]: every docker call after Java's run is read-only, or names medioo-web and
# nothing of Java's (the web run's JAVA_INTERNAL_URL value aside). Derived from what was called, so `restart`,
# `container rm`, `update`, `exec` on medscribe-ai, or a call that names no container at all, all fail it.
java_untouched_after_run() {
  local re="${1:-$JAVA_RUN_RE}" run slice bad
  run=$(first_line "$re")
  [ -n "$run" ] || { fail "$CASE: Java was never run, so there is nothing to check after it"; return; }
  slice=$(tail -n +"$((run + 1))" "$FAKE_DIR/calls.log" | grep '^docker ')
  [ -n "$slice" ] || { fail "$CASE: no docker call after Java's run: the Java-untouched check would pass vacuously"; return; }
  bad=$(printf '%s\n' "$slice" | grep -vE "$READ_ONLY_RE" \
    | awk '{s = $0; gsub(/JAVA_INTERNAL_URL=http:\/\/medscribe-ai:8086/, "", s); if (index(s, "medscribe-ai") || !index(s, "medioo-web")) print}')
  [ -z "$bad" ] || fail "$CASE: after Java's run (line $run), a call that is not read-only and not medioo-web's alone: $(printf '%s' "$bad" | head -1)"
}

# web_touches: line numbers of every call that mentions medioo-web other than the prune's `docker rmi` (disk, done
# before the floor on purpose) and read-only calls. Anywhere in the log, not only after Java's pull: a web step moved
# BEFORE Java must show up here too.
web_touches() {
  grep -nE 'medioo-web' "$FAKE_DIR/calls.log" | grep -vE '^[0-9]+:docker rmi ' \
    | awk -F: -v ro="$READ_ONLY_RE" '{line = substr($0, index($0, ":") + 1); if (line !~ ro) print $1}'
}

# web_last_run_is <tag>: the last medioo-web `docker run` is for <tag>, and carries GIT_SHA=<tag>
web_last_run_is() {
  local last; last=$(grep -E "$WEB_ANY_RUN_RE" "$FAKE_DIR/calls.log" | tail -1)
  [ "${last##* }" = "$WEB:$1" ] || fail "$CASE: the last medioo-web run is [${last##* }], expected $WEB:$1"
  [[ "$last" == *" -e GIT_SHA=$1 "* ]] || fail "$CASE: the last medioo-web run does not pass GIT_SHA=$1: $last"
}

# 1. the function itself: deploy_web <tag> exists, and sourced alone deploys medioo-web and exits 0
new_case function
FAKE_NETWORK=1 PATH="$BIN:$PATH" DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" && deploy_web "$2"' _ "$DEPLOY" "$NEW" \
  > "$FAKE_DIR/out.log" 2>&1 || fail "$CASE: deploy_web $NEW failed: $(out)"
[ -n "$(first_line "$WEB_RUN_RE")" ] || fail "$CASE: deploy_web never ran $WEB:$NEW: $(out)"

# 2. (a) Java first: the Java rm and run, and its health, all come before anything mentions medioo-web; the web
#    probe, run for real against the stub, passes for the right service, sha and image
new_case java-first
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
JAVA_PULL=$(first_line "^docker pull $REPO:$NEW\$")
JAVA_RM=$(first_line "$JAVA_GONE_RE"); JAVA_RUN=$(first_line "$JAVA_RUN_RE")
JAVA_HEALTH=$(last_line '^curl .*actuator/health')
WEB_FIRST=$(web_touches | head -1)
WEB_RUN=$(first_line "$WEB_RUN_RE")
if [ -z "$JAVA_PULL" ] || [ -z "$JAVA_RM" ] || [ -z "$JAVA_RUN" ] || [ -z "$JAVA_HEALTH" ] || [ -z "$WEB_RUN" ] || [ -z "$WEB_FIRST" ]; then
  fail "$CASE: missing a step (java pull '$JAVA_PULL', rm '$JAVA_RM', run '$JAVA_RUN', health '$JAVA_HEALTH', web first '$WEB_FIRST', web run '$WEB_RUN'): $(out)"
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
java_untouched_after_run
grep -q "medioo-web healthy" "$FAKE_DIR/out.log" || fail "$CASE: no 'medioo-web healthy' line: $(out)"
[ "$(count "$EXEC_RE")" = 1 ] || fail "$CASE: $(count "$EXEC_RE") health tries for a healthy medioo-web, expected 1"
# the catalog probe runs once, inside the new container, after its health probe passed (plan W2c)
[ "$(count "$CATALOG_EXEC_RE")" = 1 ] || fail "$CASE: $(count "$CATALOG_EXEC_RE") catalog probes, expected 1"
[ "$(first_line "$EXEC_RE")" -lt "$(first_line "$CATALOG_EXEC_RE")" ] 2>/dev/null \
  || fail "$CASE: the catalog probe (line $(first_line "$CATALOG_EXEC_RE")) did not follow the health probe (line $(first_line "$EXEC_RE"))"

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

# 4. no edge network: medioo-web would be unreachable both ways (Caddy cannot reach it, it cannot reach Java), and
#    its in-container probe would still pass. Exit 1 before the running medioo-web is touched; Java stays deployed.
new_case no-network
if run_deploy; then fail "$CASE: exited 0 with no edge network for medioo-web: $(out)"; fi
[ -n "$(first_line "$JAVA_RUN_RE")" ] || fail "$CASE: Java was not deployed"
grep -qF 'ERROR: network n8n_edge not found; medioo-web would be unreachable' "$FAKE_DIR/out.log" \
  || fail "$CASE: no 'ERROR: network n8n_edge not found; medioo-web would be unreachable' line: $(out)"
[ -z "$(first_line "$WEB_RM_RE")" ] || fail "$CASE: the running medioo-web was removed with no network to start its replacement on"
[ -z "$(first_line "$WEB_ANY_RUN_RE")" ] || fail "$CASE: a medioo-web container was started with no edge network"

# 5. (b) GHCR has no web image for this sha (every sha before W1b): exit 0, Java deployed, the log line carrying
#    docker's own error, the newer medioo-web already running left alone (not removed, not replaced)
new_case no-web-image
run_deploy FAKE_NETWORK=1 FAKE_PULL_FAIL="$WEB:$NEW" || fail "$CASE: deploy.sh failed with no web image: $(out)"
[ -n "$(first_line "$JAVA_RUN_RE")" ] || fail "$CASE: Java was not deployed"
[ -n "$(first_line "^docker pull $WEB:$NEW\$")" ] || fail "$CASE: the web image was never pulled, so 'not found' is not docker's answer"
grep -qF "web image $NEW not found; Java-only deploy (Error response from daemon: manifest unknown)" "$FAKE_DIR/out.log" \
  || fail "$CASE: no 'web image $NEW not found; Java-only deploy (Error response from daemon: manifest unknown)' line: $(out)"
[ -z "$(first_line "$WEB_RM_RE")" ] || fail "$CASE: the running medioo-web was removed although there was nothing to replace it with"
[ -z "$(first_line "$WEB_ANY_RUN_RE")" ] || fail "$CASE: a medioo-web container was started"

# 6. the same missing image when CI says the image must exist (it just pushed it): a real failure, exit 1, from
#    the WEB_IMAGE_REQUIRED branch with docker's error, and Java, already healthy, stays
new_case web-image-required
if run_deploy FAKE_NETWORK=1 FAKE_PULL_FAIL="$WEB:$NEW" WEB_IMAGE_REQUIRED=1; then
  fail "$CASE: exited 0 with WEB_IMAGE_REQUIRED=1 and no web image: $(out)"
fi
[ -n "$(first_line "^docker pull $WEB:$NEW\$")" ] || fail "$CASE: the web image was never pulled"
grep -qF 'WEB_IMAGE_REQUIRED=1 (Error response from daemon: manifest unknown)' "$FAKE_DIR/out.log" \
  || fail "$CASE: no 'WEB_IMAGE_REQUIRED=1 (Error response from daemon: manifest unknown)' line: $(out)"
java_untouched_after_run
[ -z "$(first_line "$WEB_RM_RE")" ] || fail "$CASE: the running medioo-web was removed"

# 7-8. a pull error that is NOT a missing tag (auth on the private package, a full disk) is a fault, not a pre-W1b
#    sha: exit 1 with docker's error even when WEB_IMAGE_REQUIRED is unset (a manual run), medioo-web untouched
for spec in 'pull-denied:denied' 'pull-no-space:write /var/lib/docker/tmp/GetImageBlob: no space left on device'; do
  new_case "${spec%%:*}"; ERR="${spec#*:}"
  if run_deploy FAKE_NETWORK=1 FAKE_PULL_FAIL="$WEB:$NEW" FAKE_PULL_ERR="$ERR"; then
    fail "$CASE: exited 0 when the web pull failed with '$ERR' (not a missing tag): $(out)"
  fi
  grep -qF "ERROR: could not pull web image $NEW: Error response from daemon: $ERR" "$FAKE_DIR/out.log" \
    || fail "$CASE: no 'ERROR: could not pull web image $NEW: Error response from daemon: $ERR' line: $(out)"
  grep -qF 'Java-only deploy' "$FAKE_DIR/out.log" && fail "$CASE: a '$ERR' pull failure was reported as a missing image"
  [ -z "$(first_line "$WEB_RM_RE")" ] || fail "$CASE: the running medioo-web was removed"
  [ -z "$(first_line "$WEB_ANY_RUN_RE")" ] || fail "$CASE: a medioo-web container was started"
  java_untouched_after_run
done

# 9. the pull fails (logged out, private package) but that tag is already on disk: deploy the local copy, exit 0
new_case pull-denied-local
run_deploy FAKE_NETWORK=1 IMAGE_TAG=5c2b9f3 FAKE_PULL_FAIL="$WEB:5c2b9f3" FAKE_PULL_ERR=denied \
  || fail "$CASE: deploy.sh failed although $WEB:5c2b9f3 is on disk: $(out)"
grep -qF 'web pull failed (Error response from daemon: denied); using the local 5c2b9f3' "$FAKE_DIR/out.log" \
  || fail "$CASE: no 'web pull failed (Error response from daemon: denied); using the local 5c2b9f3' line: $(out)"
web_last_run_is 5c2b9f3

# 10. (c) medioo-web never healthy: exit 1 after 30 tries, its last 50 log lines printed, the previous medioo-web
#    put back (and healthy), and Java (healthy before the web step began) untouched
new_case web-unhealthy
if run_deploy FAKE_NETWORK=1 FAKE_WEB_BAD_REF="$WEB:$NEW"; then fail "$CASE: exited 0 with medioo-web unhealthy: $(out)"; fi
java_untouched_after_run
NEW_RUN=$(first_line "$WEB_RUN_RE"); RESTORE_RUN=$(first_line "^docker run .* $WEB:$W_RUN\$")
[ -n "$NEW_RUN" ] || fail "$CASE: the new medioo-web was never run"
[ "$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE")" = 30 ] \
  || fail "$CASE: $(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE") health tries, expected 30 (60 s at 2 s)"
grep -q '^docker logs --tail 50 medioo-web$' "$FAKE_DIR/calls.log" || fail "$CASE: the last 50 log lines were not printed"
grep -q 'fake container log line' "$FAKE_DIR/out.log" || fail "$CASE: the container's log lines did not reach the output"
grep -q 'medioo-web did not become healthy in 60s' "$FAKE_DIR/out.log" || fail "$CASE: no timeout line: $(out)"
[ -n "$RESTORE_RUN" ] || fail "$CASE: the previous medioo-web ($W_RUN) was not put back: $(out)"
web_last_run_is "$W_RUN"
grep -qF "previous medioo-web ($W_RUN) is back and healthy" "$FAKE_DIR/out.log" || fail "$CASE: the restored medioo-web was not checked healthy: $(out)"

# 11. medioo-web slow to start (healthy on the third try): waits, exits 0
new_case web-slow
run_deploy FAKE_NETWORK=1 FAKE_WEB_HEALTH=2 || fail "$CASE: deploy.sh failed although medioo-web became healthy: $(out)"
[ "$(count "$EXEC_RE")" = 3 ] || fail "$CASE: $(count "$EXEC_RE") health tries, expected 3"

# 11b. (W2c) the shipped image serves the component catalog: a 200 with catalog states (an image built with
#    MEDIOO_CATALOG=1), or a 404 whose body still renders one. Healthy by /app/healthz, so this is the catalog probe's
#    own refusal: exit 1 at once (not 30 tries), its message, the previous medioo-web put back healthy, Java untouched.
for spec in "catalog-200:200" "catalog-404-states:404-states"; do
  new_case "${spec%%:*}"
  if run_deploy FAKE_NETWORK=1 FAKE_CATALOG="${spec#*:}" FAKE_CATALOG_REF="$WEB:$NEW"; then
    fail "$CASE: exited 0 though the new medioo-web serves the catalog (${spec#*:}): $(out)"
  fi
  java_untouched_after_run
  NEW_RUN=$(first_line "$WEB_RUN_RE"); RESTORE_RUN=$(first_line "^docker run .* $WEB:$W_RUN\$")
  [ "$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE")" = 1 ] \
    || fail "$CASE: $(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE") health tries for the new medioo-web, expected 1"
  [ "$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$CATALOG_EXEC_RE")" = 1 ] \
    || fail "$CASE: $(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$CATALOG_EXEC_RE") catalog probes of the new medioo-web, expected 1"
  grep -q "medioo-web (sha $NEW) answers /app/catalog-test with something other than a 404 without catalog states.*(catalog probe)" \
    "$FAKE_DIR/out.log" || fail "$CASE: no catalog probe message: $(out)"
  # the answer the probe saw is in the message, so a served catalog reads apart from a probe that got no answer
  SAW="status=$( [ "${spec#*:}" = 200 ] && echo 200 || echo 404) states=yes"
  grep -qF "($SAW)" "$FAKE_DIR/out.log" || fail "$CASE: the catalog probe message does not carry what it saw ($SAW): $(out)"
  grep -q "medioo-web healthy .*(sha $NEW)" "$FAKE_DIR/out.log" && fail "$CASE: medioo-web $NEW reported healthy while serving the catalog"
  [ -n "$RESTORE_RUN" ] || fail "$CASE: the previous medioo-web ($W_RUN) was not put back: $(out)"
  web_last_run_is "$W_RUN"
  grep -qF "previous medioo-web ($W_RUN) is back and healthy" "$FAKE_DIR/out.log" || fail "$CASE: the restored medioo-web was not checked healthy: $(out)"
done

# 11b2. (W4) aCatalogShellTestPageFailsTheProbe: every catalog-only page is probed, not only the first. The shell's
#    board page (/app/shell-test) served by the new image, the component catalog itself a clean 404: exit 1 at once,
#    the message naming /app/shell-test, the previous medioo-web put back, Java untouched.
new_case catalog-shell-test
if run_deploy FAKE_NETWORK=1 FAKE_CATALOG=200 FAKE_CATALOG_PAGE=/app/shell-test FAKE_CATALOG_REF="$WEB:$NEW"; then
  fail "$CASE: exited 0 though the new medioo-web serves /app/shell-test: $(out)"
fi
java_untouched_after_run
NEW_RUN=$(first_line "$WEB_RUN_RE"); RESTORE_RUN=$(first_line "^docker run .* $WEB:$W_RUN\$")
[ "$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$CATALOG_EXEC_RE")" = 1 ] \
  || fail "$CASE: $(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$CATALOG_EXEC_RE") catalog probes of the new medioo-web, expected 1"
grep -qF "medioo-web (sha $NEW) answers /app/shell-test with something other than a 404 without catalog states (status=200 states=yes)" \
  "$FAKE_DIR/out.log" || fail "$CASE: no catalog probe message naming /app/shell-test: $(out)"
grep -q "medioo-web healthy .*(sha $NEW)" "$FAKE_DIR/out.log" && fail "$CASE: medioo-web $NEW reported healthy while serving /app/shell-test"
[ -n "$RESTORE_RUN" ] || fail "$CASE: the previous medioo-web ($W_RUN) was not put back: $(out)"
web_last_run_is "$W_RUN"
#    and a healthy deploy names every page it found a 404, so the list it probed is visible in the log
new_case catalog-pages-named
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
grep -q "medioo-web healthy .*(sha $NEW); /app/catalog-test is a 404, /app/shell-test is a 404$" "$FAKE_DIR/out.log" \
  || fail "$CASE: the healthy line does not name both catalog-only pages: $(out)"

# 11c. (W2c) the catalog probe got NO answer: the first request in a new container that renders React (healthz is a
#    route handler and warms nothing) can be slow on the shared, memory-capped box. No answer is not a served
#    catalog: it is retried within the same deadline and 30-try budget, and only a definitive answer fails at once.
#    hang-once: the first catalog fetch times out (2 s), the second answers 404: the deploy succeeds, 2 catalog probes.
new_case catalog-hang-once
START=$SECONDS
run_deploy FAKE_NETWORK=1 FAKE_CATALOG=hang-once FAKE_CATALOG_REF="$WEB:$NEW" \
  || fail "$CASE: a catalog probe that timed out once and then answered 404 failed the deploy: $(out)"
[ "$(count "$CATALOG_EXEC_RE")" = 2 ] || fail "$CASE: $(count "$CATALOG_EXEC_RE") catalog probes, expected 2 (one timed out, one 404)"
[ "$(count "$EXEC_RE")" = 1 ] || fail "$CASE: $(count "$EXEC_RE") health tries, expected 1 (healthz is not re-probed once it passed)"
grep -q "catalog probe got no answer from /app/catalog-test (no-answer TimeoutError).*retrying" "$FAKE_DIR/out.log" \
  || fail "$CASE: no retry line naming the timeout: $(out)"
grep -q "medioo-web healthy .*(sha $NEW); /app/catalog-test is a 404" "$FAKE_DIR/out.log" || fail "$CASE: not reported healthy: $(out)"
grep -q 'something other than a 404' "$FAKE_DIR/out.log" && fail "$CASE: a timeout was reported as a served catalog"
web_last_run_is "$NEW"

#    hang and refused: the catalog path never answers. Retried, then the deploy fails within the wall-clock deadline,
#    and the logged reason is no answer, never "serves the catalog". The previous medioo-web is put back.
for spec in "catalog-hang:hang:TimeoutError" "catalog-refused:refused:TypeError"; do
  IFS=: read -r name mode err <<< "$spec"
  new_case "$name"
  START=$SECONDS
  if RUN_LIMIT=60 run_deploy FAKE_NETWORK=1 FAKE_CATALOG="$mode" FAKE_CATALOG_REF="$WEB:$NEW" WEB_HEALTH_DEADLINE_S=7; then
    fail "$CASE: exited 0 though /app/catalog-test never answered: $(out)"
  fi
  [ $((SECONDS - START)) -lt 40 ] || fail "$CASE: took $((SECONDS - START))s with a 7 s deadline"
  NEW_RUN=$(first_line "$WEB_RUN_RE"); RESTORE_RUN=$(first_line "^docker run .* $WEB:$W_RUN\$")
  N_CAT=$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$CATALOG_EXEC_RE")
  [ "$N_CAT" -gt 1 ] || fail "$CASE: $N_CAT catalog probes of the new medioo-web: no answer was not retried"
  [ "$N_CAT" -le 30 ] || fail "$CASE: $N_CAT catalog probes: past the 30-try budget"
  grep -q "ERROR: medioo-web (sha $NEW) passed /app/healthz but /app/catalog-test gave no answer in 7s (last: no-answer $err) (catalog probe)" \
    "$FAKE_DIR/out.log" || fail "$CASE: no 'gave no answer' line naming $err: $(out)"
  grep -q 'something other than a 404' "$FAKE_DIR/out.log" && fail "$CASE: no answer was reported as a served catalog"
  [ -n "$RESTORE_RUN" ] || fail "$CASE: the previous medioo-web ($W_RUN) was not put back: $(out)"
  web_last_run_is "$W_RUN"
  grep -qF "previous medioo-web ($W_RUN) is back and healthy" "$FAKE_DIR/out.log" || fail "$CASE: the restored medioo-web was not checked healthy: $(out)"
  java_untouched_after_run
done

# 12-16. the probe itself, executed: /app/healthz answers, but not for this deploy. Each is 30 tries and exit 1.
for spec in "wrong-sha:FAKE_HEALTHZ_SHA=deadbee" "wrong-image:FAKE_HEALTHZ_BUILD=$W_RUN" "wrong-service:FAKE_HEALTHZ_SERVICE=medscribe-ai" \
            "status-500:FAKE_HEALTHZ_STATUS=500" "not-json:FAKE_HEALTHZ_BODY=<html>ok</html>"; do
  new_case "probe-${spec%%:*}"
  if run_deploy FAKE_NETWORK=1 "${spec#*:}"; then fail "$CASE: exited 0 though /app/healthz answered with ${spec#*:}: $(out)"; fi
  NEW_RUN=$(first_line "$WEB_RUN_RE"); RESTORE_RUN=$(first_line "^docker run .* $WEB:$W_RUN\$")
  [ "$(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE")" = 30 ] \
    || fail "$CASE: $(count_between "${NEW_RUN:-0}" "$RESTORE_RUN" "$EXEC_RE") health tries for the new medioo-web with ${spec#*:}, expected 30"
  grep -q "medioo-web healthy .*(sha $NEW)" "$FAKE_DIR/out.log" && fail "$CASE: medioo-web $NEW reported healthy with ${spec#*:}"
done

# 17. a hung `docker exec` cannot stall the deploy: each try is bounded, and the whole wait is a wall-clock deadline
new_case exec-hangs
START=$SECONDS
if RUN_LIMIT=40 run_deploy FAKE_NETWORK=1 FAKE_EXEC_HANG=1 WEB_PROBE_TIMEOUT_S=1 WEB_HEALTH_DEADLINE_S=3; then
  fail "$CASE: exited 0 with docker exec hanging: $(out)"
fi
[ $((SECONDS - START)) -lt 20 ] || fail "$CASE: took $((SECONDS - START))s with a 1 s try and a 3 s deadline"
[ "$(count "$EXEC_RE")" -lt 30 ] || fail "$CASE: $(count "$EXEC_RE") tries: the wall-clock deadline did not end the wait"

# 18. a server that accepts and never answers: the fetch has its own deadline, shorter than the per-try timeout
new_case fetch-hangs
START=$SECONDS
if RUN_LIMIT=40 run_deploy FAKE_NETWORK=1 FAKE_HEALTHZ_STATUS=hang WEB_PROBE_TIMEOUT_S=30 WEB_HEALTH_DEADLINE_S=1; then
  fail "$CASE: exited 0 with /app/healthz hanging: $(out)"
fi
[ $((SECONDS - START)) -lt 15 ] || fail "$CASE: took $((SECONDS - START))s: the probe's fetch has no deadline of its own"

# 19. Java never healthy: exit 1; nothing after the Java pull mentions medioo-web at all, and nothing anywhere
#     touches it beyond the prune (a web step moved before Java must fail here too)
new_case java-unhealthy
if run_deploy FAKE_NETWORK=1 FAKE_CURL_FAIL=1; then fail "$CASE: exited 0 with Java unhealthy: $(out)"; fi
JAVA_PULL=$(first_line "^docker pull $REPO:$NEW\$")
if [ -z "$JAVA_PULL" ]; then
  fail "$CASE: Java was never pulled: $(out)"
else
  AFTER=$(tail -n +"$((JAVA_PULL + 1))" "$FAKE_DIR/calls.log")
  [ -n "$AFTER" ] || fail "$CASE: nothing after the Java pull: the check below would pass vacuously"
  TOUCH=$(printf '%s\n' "$AFTER" | grep -m1 'medioo-web')
  [ -z "$TOUCH" ] || fail "$CASE: medioo-web touched although Java was not healthy: $TOUCH"
fi
FIRST_TOUCH=$(web_touches | head -1)
[ -z "$FIRST_TOUCH" ] || fail "$CASE: medioo-web touched although Java never became healthy: $(sed -n "${FIRST_TOUCH}p" "$FAKE_DIR/calls.log")"

# 20. both repositories pruned to the newest 3, never the running image, never bddrug's, all before the floor is
#     read and before the Java pull
new_case prune-both
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
GOT=$(awk '$2 == "rmi" {print $3}' "$FAKE_DIR/calls.log" | LC_ALL=C sort | tr '\n' ' ')
WANT=$(printf '%s\n' "$REPO:a90d4b1" "$REPO:$OLDEST" "$WEB:a90d4b1" "$WEB:$OLDEST" | LC_ALL=C sort | tr '\n' ' ')
[ "$GOT" = "$WANT" ] || fail "$CASE: removed [$GOT], expected [$WANT]"
LAST_RMI=$(last_line '^docker rmi '); DF=$(first_line '^df '); JAVA_PULL=$(first_line "^docker pull $REPO:")
if [ -n "$LAST_RMI" ] && [ -n "$DF" ] && [ -n "$JAVA_PULL" ]; then
  { [ "$LAST_RMI" -lt "$DF" ] && [ "$DF" -lt "$JAVA_PULL" ]; } \
    || fail "$CASE: expected every rmi ($LAST_RMI) < the free-space check ($DF) < the Java pull ($JAVA_PULL)"
else
  fail "$CASE: missing rmi ('$LAST_RMI'), df ('$DF') or Java pull ('$JAVA_PULL')"
fi

# 21. one IMAGE_TAG rolls back both: the oldest tag, on disk in both repositories, is the target. Neither copy is
#     pruned (a logged-out manual run could not pull it back), and medioo-web runs exactly it
new_case rollback-both
run_deploy FAKE_NETWORK=1 IMAGE_TAG="$OLDEST" || fail "$CASE: deploy.sh failed rolling back to $OLDEST: $(out)"
for ref in "$REPO:$OLDEST" "$WEB:$OLDEST"; do
  grep -qxF "docker rmi $ref" "$FAKE_DIR/calls.log" && fail "$CASE: the rollback target $ref was pruned"
done
web_last_run_is "$OLDEST"
java_untouched_after_run "^docker run .* $REPO:$OLDEST\$"

# 22. the first deploy (the box today): no medioo-web container and no web image yet. Nothing to remove, one run.
new_case first-deploy
grep -v " $WEB " "$FAKE_DIR/images.txt" > "$FAKE_DIR/images.txt.new" && mv "$FAKE_DIR/images.txt.new" "$FAKE_DIR/images.txt"
printf 'c1 %s sha256:0005\n' "$REPO:$J_RUN" > "$FAKE_DIR/running.txt"
printf 'medscribe-ai %s\n' "$REPO:$J_RUN" > "$FAKE_DIR/names.txt"; rm -f "$FAKE_DIR/container.medioo-web.env"
run_deploy FAKE_NETWORK=1 || fail "$CASE: the first deploy failed: $(out)"
[ -z "$(first_line "$WEB_RM_RE")" ] || fail "$CASE: removed a medioo-web that did not exist: $(sed -n "$(first_line "$WEB_RM_RE")p" "$FAKE_DIR/calls.log")"
[ "$(count "$WEB_ANY_RUN_RE")" = 1 ] || fail "$CASE: $(count "$WEB_ANY_RUN_RE") medioo-web runs, expected 1"
grep -q 'medioo-web healthy' "$FAKE_DIR/out.log" || fail "$CASE: no 'medioo-web healthy' line: $(out)"

# 23. docker run of medioo-web fails (for any tag): exit 1, the line saying so, no health probe of a container
#     that never started
new_case web-run-fails
if run_deploy FAKE_NETWORK=1 FAKE_RUN_FAIL="$WEB"; then fail "$CASE: exited 0 although medioo-web could not be started: $(out)"; fi
grep -qF 'ERROR: could not start medioo-web' "$FAKE_DIR/out.log" || fail "$CASE: no 'ERROR: could not start medioo-web' line: $(out)"
[ "$(count "$EXEC_RE")" = 0 ] || fail "$CASE: probed a medioo-web that never started ($(count "$EXEC_RE") execs)"
java_untouched_after_run

# 24. only the new tag fails to start: the previous medioo-web is put back and checked
new_case web-run-fails-restore
if run_deploy FAKE_NETWORK=1 FAKE_RUN_FAIL="$WEB:$NEW"; then fail "$CASE: exited 0 although medioo-web could not be started: $(out)"; fi
web_last_run_is "$W_RUN"
grep -qF "previous medioo-web ($W_RUN) is back and healthy" "$FAKE_DIR/out.log" || fail "$CASE: the previous medioo-web is not back and healthy: $(out)"

# 25. (W1c) Java off the public port: the one published port is 127.0.0.1:8086, and the health wait asks 127.0.0.1
#     (not "localhost", which can resolve to ::1, where nothing listens any more)
new_case java-loopback
run_deploy FAKE_NETWORK=1 || fail "$CASE: deploy.sh failed: $(out)"
A="$FAKE_DIR/run.medscribe-ai.args"
if [ -f "$A" ]; then
  arg_pair "$A" -p 127.0.0.1:8086:8086 || fail "$CASE: Java is not published as '-p 127.0.0.1:8086:8086': $(tr '\n' '|' < "$A")"
  PUBLISHES=$(grep -cxE -- '-p|--publish' "$A")
  [ "$PUBLISHES" = 1 ] || fail "$CASE: $PUBLISHES -p/--publish arguments, expected exactly one: $(tr '\n' '|' < "$A")"
  grep -qE -- '^(-P|--publish-all|--publish=.*|-p=.*)$' "$A" && fail "$CASE: another way of publishing a port: $(tr '\n' '|' < "$A")"
  grep -qxE '(0\.0\.0\.0:)?8086:8086' "$A" && fail "$CASE: 8086 is published on every interface: $(tr '\n' '|' < "$A")"
  grep -qE '^--network$' "$A" || fail "$CASE: Java is not on the edge network, so with no public port nothing but the box could reach it"
else
  fail "$CASE: Java was never run: $(out)"
fi
grep -qE '^curl .*http://127\.0\.0\.1:8086/actuator/health' "$FAKE_DIR/calls.log" \
  || fail "$CASE: the health wait does not ask http://127.0.0.1:8086: $(grep '^curl ' "$FAKE_DIR/calls.log" | head -1)"

# 26-35. (W1c) the routes floor: once a route has flipped, an IMAGE_TAG that is not a descendant of the recorded
#     floor sha is refused (it predates a route Caddy now sends to medioo-web), unless ROUTES_FLOOR_FORCE=1 (and
#     only that: a generic FORCE=1 left in a shell overrides nothing). Both directions.
FLOOR=4c8e1f0aa9b3d2e7c6f5a4b3c2d1e0f9a8b7c6d5
STATE_RE='^docker (pull|rm|rmi|run|stop|kill|restart) '
# refused_before_touching <why>: exit 1 with the line, and NOTHING run but the compare call(s) and the pause before its
# one retry. Derived from the log, not a list of mutating verbs: deploy.sh also mutates through `docker image prune`,
# `exec`, `login`, `network`, and any call at all before the check means the check no longer runs first.
ONLY_COMPARE_RE='/compare/|^sleep 5$'
refused_before_touching() {
  grep -qF "$1" "$FAKE_DIR/out.log" || fail "$CASE: no '$1' line: $(out)"
  [ "$(grep -vcE "$ONLY_COMPARE_RE" "$FAKE_DIR/calls.log")" = 0 ] \
    || fail "$CASE: refused, but ran something first: $(grep -vE "$ONLY_COMPARE_RE" "$FAKE_DIR/calls.log" | head -1)"
}
deployed() { [ -n "$(first_line "^docker run .* $REPO:${1:-$NEW}\$")" ] || fail "$CASE: Java ${1:-$NEW} was not deployed: $(out)"; }

new_case floor-none
run_deploy FAKE_NETWORK=1 || fail "$CASE: failed with no routes floor recorded: $(out)"
grep -qF 'no routes floor recorded' "$FAKE_DIR/out.log" || fail "$CASE: no 'no routes floor recorded' line: $(out)"
[ "$(count '/compare/')" = 0 ] || fail "$CASE: asked GitHub with no floor to compare against"
deployed

new_case floor-descendant
echo "$FLOOR /app/healthz 2026-10-21" > "$FAKE_DIR/routes-floor"
run_deploy FAKE_NETWORK=1 FAKE_COMPARE=ahead || fail "$CASE: a descendant of the floor was refused: $(out)"
# the cheapest answer that still carries the top-level status: page 2 of one commit per page has no files list
# (measured 2026-10-21: 297 KB with every file and patch -> 41 KB), so a long range cannot time the compare out
grep -qE "^curl .*/compare/$FLOOR\.\.\.$NEW\?per_page=1&page=2( |\$)" "$FAKE_DIR/calls.log" \
  || fail "$CASE: did not ask GitHub to compare $FLOOR...$NEW?per_page=1&page=2: $(grep '^curl .*compare' "$FAKE_DIR/calls.log")"
grep -qE "^curl .*--max-time 30 .*/compare/" "$FAKE_DIR/calls.log" \
  || fail "$CASE: the compare call is not bounded by --max-time 30: $(grep '^curl .*compare' "$FAKE_DIR/calls.log")"
CMP=$(first_line '/compare/'); FIRST_STATE=$(first_line "$STATE_RE")
[ -n "$CMP" ] && [ -n "$FIRST_STATE" ] && [ "$CMP" -lt "$FIRST_STATE" ] \
  || fail "$CASE: the floor check (line '$CMP') does not come before the first change to the box (line '$FIRST_STATE')"
deployed

new_case floor-identical
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
run_deploy FAKE_NETWORK=1 IMAGE_TAG="$FLOOR" FAKE_COMPARE=fail || fail "$CASE: the floor sha itself was refused: $(out)"
deployed "$FLOOR"

new_case floor-ancestor
echo "$FLOOR /app/healthz 2026-10-21" > "$FAKE_DIR/routes-floor"
if run_deploy FAKE_NETWORK=1 FAKE_COMPARE=behind; then fail "$CASE: deployed a sha older than the routes floor: $(out)"; fi
refused_before_touching "is not a descendant of the routes floor $FLOOR (GitHub: behind)"

new_case floor-diverged
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
if run_deploy FAKE_NETWORK=1 FAKE_COMPARE=diverged; then fail "$CASE: deployed a sha off the floor's line: $(out)"; fi
refused_before_touching "is not a descendant of the routes floor $FLOOR (GitHub: diverged)"

new_case floor-force
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
run_deploy FAKE_NETWORK=1 FAKE_COMPARE=behind ROUTES_FLOOR_FORCE=1 || fail "$CASE: ROUTES_FLOOR_FORCE=1 did not override the floor: $(out)"
grep -qF 'WARNING: ROUTES_FLOOR_FORCE=1' "$FAKE_DIR/out.log" || fail "$CASE: no 'WARNING: ROUTES_FLOOR_FORCE=1' line: $(out)"
deployed

# a generic FORCE=1 (scripts/draft-labels.sh reads the same name) left exported in a shell overrides nothing
new_case floor-generic-force
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
if run_deploy FAKE_NETWORK=1 FAKE_COMPARE=behind FORCE=1; then fail "$CASE: a stray FORCE=1 overrode the routes floor: $(out)"; fi
refused_before_touching "is not a descendant of the routes floor $FLOOR (GitHub: behind)"

new_case floor-unreachable
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
if run_deploy FAKE_NETWORK=1 FAKE_COMPARE=fail; then fail "$CASE: deployed although the floor could not be checked: $(out)"; fi
refused_before_touching "could not check $NEW against the routes floor $FLOOR"
[ "$(count '/compare/')" = 2 ] || fail "$CASE: asked GitHub $(count '/compare/') time(s), expected 2 (one retry)"

# one failed call (a GitHub blip) is retried once before the deploy is refused
new_case floor-retry
echo "$FLOOR" > "$FAKE_DIR/routes-floor"
run_deploy FAKE_NETWORK=1 FAKE_COMPARE=fail-once || fail "$CASE: one failed compare call refused the deploy: $(out)"
[ "$(count '/compare/')" = 2 ] || fail "$CASE: asked GitHub $(count '/compare/') time(s), expected 2"
deployed

new_case floor-malformed
echo "not-a-sha; rm -rf /" > "$FAKE_DIR/routes-floor"
if run_deploy FAKE_NETWORK=1; then fail "$CASE: deployed with an unreadable routes floor: $(out)"; fi
refused_before_touching "routes floor file"
[ -s "$FAKE_DIR/calls.log" ] && fail "$CASE: ran something with a malformed floor: $(head -1 "$FAKE_DIR/calls.log")"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-sh: $CASES cases passed"
