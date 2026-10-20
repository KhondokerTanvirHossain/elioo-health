#!/usr/bin/env bash
#
# Tests for deploy.sh's prune_repo, its free-space floor and its function-only mode, against a fake `docker`,
# `curl` and `df` on PATH: no daemon, no network, no dependence on this machine's disk.
#
#   scripts/test_deploy_prune.sh                     # tests ./deploy.sh
#   DEPLOY_SH=/path/to/copy.sh scripts/test_deploy_prune.sh   # replays the same cases against another copy
#
# Why it exists: the box is shared with n8n and bddrug, deploy.sh pulled a new ~500 MB image on every merge and
# only cleaned up with a global `docker image prune -f` AFTER a healthy start. Tagged images are never "dangling",
# so that prune removed nothing of ours; 40 old medscribe-ai tags filled the disk to 233 MB free and the next
# pull would have failed mid-deploy. The rule now: before the pull, remove old tags of exactly our repository,
# keep the newest N by creation time, never the tag being deployed, never the image of a running container,
# never anything belonging to another repository; then refuse to pull below a free-space floor.
#
# The fake `docker images` prints EVERY repository regardless of the arguments it is given, so the
# exact-repository filter is prune_repo's own job and is tested here, not delegated to the fake.
set -uo pipefail

DEPLOY="${DEPLOY_SH:-$(cd "$(dirname "$0")/.." && pwd)/deploy.sh}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0

fail() { echo "FAIL: $1"; FAILED=1; }

REPO=ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai

# Six tags, T1 the oldest .. T6 the newest. On the box tags are git shas, so their ALPHABETICAL order is random
# with respect to their age; these are chosen so that it disagrees with creation order (T1 sorts last, T6
# first), and a sort by tag name, or by the whole line, keeps the wrong ones. T3 and T4 share a DATE and differ
# only in the time of day, and sit either side of the keep-3 boundary with T3's name sorting above T4's: a
# sort on the date alone breaks the tie by name and keeps T3 instead of T4.
T1=f3a1c2e   # 2026-10-11 10:00:00  oldest
T2=a90d4b1   # 2026-10-12 10:00:00
T3=d41e7a0   # 2026-10-13 09:00:00  same date as T4, earlier
T4=5c2b9f3   # 2026-10-13 18:30:00
T5=e8f0a6d   # 2026-10-14 10:00:00
T6=0b7c3d9   # 2026-10-15 10:00:00  newest
NEW=9e1d2c4  # the tag a deploy pulls; not on the box yet

# ---- the fakes (shared with scripts/test_deploy_sh.sh) ---------------------------------------------------------
BIN="$WORK/bin"
# shellcheck source=deploy_test_fakes.sh
source "$(cd "$(dirname "$0")" && pwd)/deploy_test_fakes.sh"
write_deploy_fakes "$BIN"

# Listed out of order so prune_repo has to sort itself; two bddrug tags and a look-alike repository, all OLDER
# than every medscribe-ai tag, so a filter that leaks would pick them first; and a dangling <none> of our own
# repository, which cannot be removed by tag.
write_images() {
  cat > "$1/images.txt" <<EOF
$REPO $T4 sha256:0004 2026-10-13 18:30:00 +0000 UTC
$REPO $T1 sha256:0001 2026-10-11 10:00:00 +0000 UTC
$REPO $T6 sha256:0006 2026-10-15 10:00:00 +0000 UTC
$REPO <none> sha256:00ff 2026-10-10 10:00:00 +0000 UTC
$REPO $T2 sha256:0002 2026-10-12 10:00:00 +0000 UTC
$REPO $T5 sha256:0005 2026-10-14 10:00:00 +0000 UTC
$REPO $T3 sha256:0003 2026-10-13 09:00:00 +0000 UTC
ghcr.io/khondokertanvirhossain/bddrug-web b1 sha256:0b01 2026-09-01 10:00:00 +0000 UTC
ghcr.io/khondokertanvirhossain/bddrug-api b2 sha256:0b02 2026-09-02 10:00:00 +0000 UTC
$REPO-staging s1 sha256:05a1 2026-09-03 10:00:00 +0000 UTC
EOF
}

# new_case <name> <running.txt content>: a fresh fixture directory, exported as FAKE_DIR
new_case() {
  CASE="$1"
  CASES=$((CASES+1))
  FAKE_DIR="$WORK/$1"; export FAKE_DIR
  mkdir -p "$FAKE_DIR"; : > "$FAKE_DIR/calls.log"
  write_images "$FAKE_DIR"
  printf '%s' "$2" > "$FAKE_DIR/running.txt"
}

# run_prune <keep> [<protected tag>]: source deploy.sh for its functions only, then call prune_repo
run_prune() {
  PATH="$BIN:$PATH" DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" && prune_repo "$2" "$3" "${4:-}"' \
    _ "$DEPLOY" "$REPO" "$1" "${2:-}" > "$FAKE_DIR/out.log" 2>&1
}

# run_deploy <IMAGE_TAG> <env file>: the whole deploy.sh, executed as the box executes it. These cases are about
# Java, so GHCR has no medioo-web image for the tag (a pre-W1b sha) and the web step is skipped: some of them run
# with no edge network, which the web step rightly refuses (scripts/test_deploy_sh.sh covers that step).
run_deploy() {
  PATH="$BIN:$PATH" IMAGE_TAG="$1" ENV_FILE="$2" FAKE_PULL_FAIL="ghcr.io/khondokertanvirhossain/elioo-health/medioo-web:$1" \
    bash "$DEPLOY" > "$FAKE_DIR/out.log" 2>&1
}

out() { tr '\n' '|' < "$FAKE_DIR/out.log"; }

# refs <tag>...: the full references, sorted the same way removed() sorts, space-separated
refs() { for t in "$@"; do echo "$REPO:$t"; done | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//'; }

# the exact references prune_repo asked docker to remove, sorted, space-separated
removed() { awk '$2=="rmi" {print $3}' "$FAKE_DIR/calls.log" | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//'; }

expect_removed() {   # <expected, from refs>
  local got; got="$(removed)"
  [ "$got" = "$1" ] || fail "$CASE: removed [$got], expected [$1]. Output: $(out)"
}

no_pull() { ! grep -q '^docker pull ' "$FAKE_DIR/calls.log"; }

# arg_pair <a> <b>: the recorded Java `docker run` has argument <a> immediately followed by argument <b>
arg_pair() { awk -v a="$1" -v b="$2" 'prev == a && $0 == b {f = 1} {prev = $0} END {exit !f}' "$FAKE_DIR/run.medscribe-ai.args"; }

printf 'SOME_SETTING=x\n' > "$WORK/env"

# 1. keep 3, running is one of the newest: exactly the 3 oldest of OUR tags go, and nothing of bddrug's
new_case keep-three "c1 $REPO:$T5 sha256:0005
c2 ghcr.io/khondokertanvirhossain/bddrug-web:b1 sha256:0b01
"
run_prune 3 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed "$(refs $T1 $T2 $T3)"
if grep -E '^docker rmi ' "$FAKE_DIR/calls.log" | grep -vE "^docker rmi $REPO:[0-9a-f]+\$" > /dev/null; then
  fail "$CASE: removed something outside $REPO: $(grep '^docker rmi ' "$FAKE_DIR/calls.log" | tr '\n' '|')"
fi
grep -q '^docker image prune' "$FAKE_DIR/calls.log" && fail "$CASE: called the global docker image prune"
grep -qE '^docker rmi [^ ]*sha256:' "$FAKE_DIR/calls.log" && fail "$CASE: removed by image id (takes every tag of it)"

# 2. the running container's image is the OLDEST tag: it stays, and only the other two old ones go
new_case running-oldest "c1 $REPO:$T1 sha256:0001
"
run_prune 3 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed "$(refs $T2 $T3)"

# 3. the id rule alone: a container started by image id, whose reference names no tag
new_case running-by-id "c1 sha256:0002 sha256:0002
"
run_prune 3 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed "$(refs $T1 $T3)"

# 4. the reference rule alone: inspect yields an id that is in no image row, so only the reference protects T2
new_case running-by-ref "c1 $REPO:$T2 sha256:dead
"
run_prune 3 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed "$(refs $T1 $T3)"

# 5. the reason the reference rule exists: `docker inspect` fails, and the running oldest tag is still kept
new_case inspect-fails "c1 $REPO:$T1 sha256:0001
"
FAKE_INSPECT_FAIL=1 run_prune 3 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed "$(refs $T2 $T3)"

# 6. an empty image list and no containers: removes nothing, exits 0
new_case empty ""
: > "$FAKE_DIR/images.txt"
run_prune 3 || fail "$CASE: prune_repo exited non-zero on an empty image list: $(out)"
expect_removed ""

# 7. fewer tags than the keep count: removes nothing
new_case keep-many ""
run_prune 10 || fail "$CASE: prune_repo exited non-zero: $(out)"
expect_removed ""

# 8. one removal refused by the daemon (a stopped container still holds it): the rest still go, exit 0
new_case rmi-refused ""
FAKE_RMI_FAIL="$REPO:$T2" run_prune 3 || fail "$CASE: a refused rmi failed prune_repo (and so the deploy)"
expect_removed "$(refs $T1 $T2 $T3)"

# 9. the whole deploy: the prune runs BEFORE the pull, the global prune is gone, and with no edge network and no
#    GCP file the run line carries neither (and no empty argument where they would have been)
new_case deploy-order "c1 $REPO:$T6 sha256:0006
"
run_deploy "$NEW" "$WORK/env" || fail "$CASE: deploy.sh failed: $(tail -5 "$FAKE_DIR/out.log" | tr '\n' '|')"
PULL_LINE=$(grep -n "^docker pull $REPO:$NEW\$" "$FAKE_DIR/calls.log" | head -1 | cut -d: -f1)
LAST_RMI=$(grep -n '^docker rmi ' "$FAKE_DIR/calls.log" | tail -1 | cut -d: -f1)
[ -n "$PULL_LINE" ] || fail "$CASE: deploy.sh never pulled $REPO:$NEW"
if [ -z "$LAST_RMI" ]; then
  fail "$CASE: no old $REPO tag was removed before the pull"
elif [ -n "$PULL_LINE" ] && [ "$LAST_RMI" -gt "$PULL_LINE" ]; then
  fail "$CASE: old tags were removed after the pull (line $LAST_RMI > $PULL_LINE)"
fi
expect_removed "$(refs $T1 $T2 $T3)"
grep -q '^docker image prune' "$FAKE_DIR/calls.log" && fail "$CASE: deploy.sh still calls the global docker image prune"
if [ -f "$FAKE_DIR/run.medscribe-ai.args" ]; then
  grep -qx -- '--network' "$FAKE_DIR/run.medscribe-ai.args" && fail "$CASE: --network passed with no edge network"
  grep -qx -- '-v' "$FAKE_DIR/run.medscribe-ai.args" && fail "$CASE: a volume mounted with no GCP file"
  grep -qx '' "$FAKE_DIR/run.medscribe-ai.args" && fail "$CASE: docker run got an empty argument: $(tr '\n' '|' < "$FAKE_DIR/run.medscribe-ai.args")"
  [ "$(tail -1 "$FAKE_DIR/run.medscribe-ai.args")" = "$REPO:$NEW" ] || fail "$CASE: the image is not docker run's last argument"
else
  fail "$CASE: deploy.sh never ran a container"
fi
# the health body (disk, path, database details) must not reach the CI log, which is public (DR-38)
# (the body's fields, not a bare "/app/": deploy.sh legitimately logs medioo-web's /app/healthz)
grep -qF -e 'diskSpace' -e 'PostgreSQL' -e '"path":"/app/' "$FAKE_DIR/out.log" && fail "$CASE: the health body was printed: $(out)"
grep -q 'Healthy after .*"status":"UP"' "$FAKE_DIR/out.log" || fail "$CASE: no healthy status logged: $(out)"

# 10. the run line on the box's real path: edge network present and a GCP credentials file to mount
new_case deploy-args "c1 $REPO:$T6 sha256:0006
"
: > "$WORK/gcp.json"
printf 'SOME_SETTING=x\nGCP_CREDENTIALS_HOST_PATH=%s\n' "$WORK/gcp.json" > "$WORK/env-gcp"
FAKE_NETWORK=1 run_deploy "$NEW" "$WORK/env-gcp" || fail "$CASE: deploy.sh failed: $(tail -5 "$FAKE_DIR/out.log" | tr '\n' '|')"
if [ -f "$FAKE_DIR/run.medscribe-ai.args" ]; then
  arg_pair --network n8n_edge || fail "$CASE: no '--network n8n_edge' in docker run: $(tr '\n' '|' < "$FAKE_DIR/run.medscribe-ai.args")"
  arg_pair -v "$WORK/gcp.json:/secrets/gcp-credentials.json:ro" \
    || fail "$CASE: GCP credentials not mounted read-only: $(tr '\n' '|' < "$FAKE_DIR/run.medscribe-ai.args")"
  [ "$(tail -1 "$FAKE_DIR/run.medscribe-ai.args")" = "$REPO:$NEW" ] || fail "$CASE: the image is not docker run's last argument"
else
  fail "$CASE: deploy.sh never ran a container"
fi

# 11. a rollback to an old tag that is still on disk (IMAGE_TAG=T1, T6 running): T1 is not removed and pulled
#     again, so a GHCR outage during an incident cannot take the known-good image away
new_case rollback-keeps-target "c1 $REPO:$T6 sha256:0006
"
run_deploy "$T1" "$WORK/env" || fail "$CASE: deploy.sh failed: $(tail -5 "$FAKE_DIR/out.log" | tr '\n' '|')"
expect_removed "$(refs $T2 $T3)"
grep -q "^docker pull $REPO:$T1\$" "$FAKE_DIR/calls.log" || fail "$CASE: deploy.sh never pulled $REPO:$T1"

# 12. too little disk after the prune: refuse BEFORE the pull, exit non-zero, leave the running container alone
new_case low-disk "c1 $REPO:$T6 sha256:0006
"
if FAKE_DF_KB=500000 run_deploy "$NEW" "$WORK/env"; then fail "$CASE: deploy.sh exited 0 with 500000 KB free"; fi
no_pull || fail "$CASE: deploy.sh pulled with 500000 KB free: $(grep '^docker pull' "$FAKE_DIR/calls.log")"
grep -qE '^docker (rm|run) ' "$FAKE_DIR/calls.log" && fail "$CASE: deploy.sh touched the running container on a full disk"
grep -q 'refusing to pull' "$FAKE_DIR/out.log" || fail "$CASE: no 'refusing to pull' in the log: $(out)"
[ -n "$(removed)" ] || fail "$CASE: the prune did not run before the free-space check (it may be what frees it)"

# 13. free space that cannot be read counts as too little
new_case df-unreadable "c1 $REPO:$T6 sha256:0006
"
if FAKE_DF_KB=none run_deploy "$NEW" "$WORK/env"; then fail "$CASE: deploy.sh exited 0 without knowing the free space"; fi
no_pull || fail "$CASE: deploy.sh pulled without knowing the free space"

# 14. DEPLOY_FUNCTIONS_ONLY=1 on an EXECUTED deploy.sh (a stray export on the box) must not be a silent green no-op
new_case functions-only-executed "c1 $REPO:$T6 sha256:0006
"
if PATH="$BIN:$PATH" DEPLOY_FUNCTIONS_ONLY=1 IMAGE_TAG="$NEW" ENV_FILE="$WORK/env" bash "$DEPLOY" > "$FAKE_DIR/out.log" 2>&1; then
  fail "$CASE: DEPLOY_FUNCTIONS_ONLY=1 bash deploy.sh exited 0 having deployed nothing: $(out)"
fi
no_pull || fail "$CASE: deploy.sh pulled although it then refused"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-prune: $CASES cases passed"
