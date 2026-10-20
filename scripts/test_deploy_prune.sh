#!/usr/bin/env bash
#
# Tests for prune_repo in deploy.sh, against a fake `docker` (and `curl`) on PATH: no daemon, no network.
#
#   scripts/test_deploy_prune.sh
#
# Why it exists: the box is shared with n8n and bddrug, deploy.sh pulled a new ~500 MB image on every merge and
# only cleaned up with a global `docker image prune -f` AFTER a healthy start. Tagged images are never "dangling",
# so that prune removed nothing of ours; 40 old medscribe-ai tags filled the disk to 233 MB free and the next
# pull would have failed mid-deploy. The rule now: before the pull, remove old tags of exactly our repository,
# keep the newest N, never the image of a running container, never anything belonging to another repository.
#
# The fake `docker images` prints EVERY repository regardless of the arguments it is given, so the
# exact-repository filter is prune_repo's own job and is tested here, not delegated to the fake.
set -uo pipefail

DEPLOY="$(cd "$(dirname "$0")/.." && pwd)/deploy.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0

fail() { echo "FAIL: $1"; FAILED=1; }

REPO=ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai

# ---- the fake docker / curl ---------------------------------------------------------------------------------
BIN="$WORK/bin"
mkdir -p "$BIN"
cat > "$BIN/docker" <<'FAKE'
#!/usr/bin/env bash
# Records every call, one per line, then answers from fixture files in $FAKE_DIR:
#   images.txt   "<repository> <tag> <id> <created...>", any order, every repository
#   running.txt  "<container id> <image ref it was started with> <image id>", one per running container
printf 'docker %s\n' "$*" >> "$FAKE_DIR/calls.log"
args="$*"
case "$1" in
  images)
    cat "$FAKE_DIR/images.txt" ;;
  ps)
    if [[ "$args" == *'{{.Names}}'* ]]; then echo medscribe-ai
    elif [[ "$args" == *'{{.Image}}'* ]]; then awk '{print $2}' "$FAKE_DIR/running.txt"
    elif [[ " $args " == *' -q '* ]]; then awk '{print $1}' "$FAKE_DIR/running.txt"
    fi ;;
  inspect)
    shift
    for a in "$@"; do
      case "$a" in --format|-f) continue ;; '{{.Image}}') continue ;; esac
      awk -v c="$a" '$1==c {print $3}' "$FAKE_DIR/running.txt"
    done ;;
  rmi)
    if [ -n "${FAKE_RMI_FAIL:-}" ] && [ "$2" = "$FAKE_RMI_FAIL" ]; then
      echo "Error response from daemon: conflict: image is being used by stopped container" >&2; exit 1
    fi ;;
  network)
    exit 1 ;;   # no edge network: deploy.sh falls back to the host port
  *) ;;         # pull, rm, run, logs, image prune: recorded, succeed
esac
exit 0
FAKE
cat > "$BIN/curl" <<'FAKE'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$FAKE_DIR/calls.log"
echo '{"status":"UP"}'
FAKE
chmod +x "$BIN/docker" "$BIN/curl"

# Six medscribe-ai tags m1 (oldest) .. m6 (newest), listed out of order so prune_repo has to sort by time
# itself; two bddrug tags and a look-alike repository, all OLDER than every medscribe-ai tag, so a filter that
# leaks would pick them first; and a dangling <none> of our own repository, which cannot be removed by tag.
write_images() {
  cat > "$1/images.txt" <<EOF
$REPO m4 sha256:0004 2026-10-14 10:00:00 +0000 UTC
$REPO m1 sha256:0001 2026-10-11 10:00:00 +0000 UTC
$REPO m6 sha256:0006 2026-10-16 10:00:00 +0000 UTC
$REPO <none> sha256:00ff 2026-10-10 10:00:00 +0000 UTC
$REPO m2 sha256:0002 2026-10-12 10:00:00 +0000 UTC
$REPO m5 sha256:0005 2026-10-15 10:00:00 +0000 UTC
$REPO m3 sha256:0003 2026-10-13 09:59:59 +0000 UTC
ghcr.io/khondokertanvirhossain/bddrug-web b1 sha256:0b01 2026-09-01 10:00:00 +0000 UTC
ghcr.io/khondokertanvirhossain/bddrug-api b2 sha256:0b02 2026-09-02 10:00:00 +0000 UTC
$REPO-staging s1 sha256:05a1 2026-09-03 10:00:00 +0000 UTC
EOF
}

# new_case <name> <running.txt content>: a fresh fixture directory, exported as FAKE_DIR
new_case() {
  CASES=$((CASES+1))
  FAKE_DIR="$WORK/$1"; export FAKE_DIR
  mkdir -p "$FAKE_DIR"; : > "$FAKE_DIR/calls.log"
  write_images "$FAKE_DIR"
  printf '%s' "$2" > "$FAKE_DIR/running.txt"
}

# run_prune <keep>: source deploy.sh for its functions only, then call prune_repo on our repository
run_prune() {
  PATH="$BIN:$PATH" DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" && prune_repo "$2" "$3"' _ "$DEPLOY" "$REPO" "$1" \
    > "$FAKE_DIR/out.log" 2>&1
}

# the exact references prune_repo asked docker to remove, sorted, space-separated
removed() { awk '$2=="rmi" {print $3}' "$FAKE_DIR/calls.log" | sort | tr '\n' ' ' | sed 's/ $//'; }

expect_removed() {   # <case> <expected, sorted, space-separated>
  local got; got="$(removed)"
  [ "$got" = "$2" ] || fail "$1: removed [$got], expected [$2]. Output: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
}

# 1. keep 3, running is one of the newest: exactly the 3 oldest of OUR tags go, and nothing of bddrug's
new_case keep-three "c1 $REPO:m5 sha256:0005
c2 ghcr.io/khondokertanvirhossain/bddrug-web:b1 sha256:0b01
"
run_prune 3 || fail "keep-three: prune_repo exited non-zero: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
expect_removed keep-three "$REPO:m1 $REPO:m2 $REPO:m3"
if grep -E '^docker rmi ' "$FAKE_DIR/calls.log" | grep -vE "^docker rmi $REPO:m[0-9]+\$" > /dev/null; then
  fail "keep-three: removed something outside $REPO: $(grep '^docker rmi ' "$FAKE_DIR/calls.log" | tr '\n' '|')"
fi
grep -q '^docker image prune' "$FAKE_DIR/calls.log" && fail "keep-three: called the global docker image prune"
grep -qE '^docker rmi [^ ]*sha256:' "$FAKE_DIR/calls.log" && fail "keep-three: removed by image id (takes every tag of it)"

# 2. the running container's image is the OLDEST tag: it stays, and only the other two old ones go
new_case running-oldest "c1 $REPO:m1 sha256:0001
"
run_prune 3 || fail "running-oldest: prune_repo exited non-zero: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
expect_removed running-oldest "$REPO:m2 $REPO:m3"

# 3. a container started by image id (or whose tag has since moved): protected by id, not only by reference
new_case running-by-id "c1 sha256:0002 sha256:0002
"
run_prune 3 || fail "running-by-id: prune_repo exited non-zero: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
expect_removed running-by-id "$REPO:m1 $REPO:m3"

# 4. an empty image list and no containers: removes nothing, exits 0
new_case empty ""
: > "$FAKE_DIR/images.txt"
run_prune 3 || fail "empty: prune_repo exited non-zero on an empty image list: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
expect_removed empty ""

# 5. fewer tags than the keep count: removes nothing
new_case keep-many ""
run_prune 10 || fail "keep-many: prune_repo exited non-zero: $(tr '\n' '|' < "$FAKE_DIR/out.log")"
expect_removed keep-many ""

# 6. one removal refused by the daemon (a stopped container still holds it): the rest still go, exit 0
new_case rmi-refused ""
FAKE_RMI_FAIL="$REPO:m2" run_prune 3 || fail "rmi-refused: a refused rmi failed prune_repo (and so the deploy)"
expect_removed rmi-refused "$REPO:m1 $REPO:m2 $REPO:m3"

# 7. the whole deploy: the prune runs BEFORE the pull, and the global prune is gone
new_case deploy-order "c1 $REPO:m6 sha256:0006
"
printf 'SOME_SETTING=x\n' > "$WORK/env"
if ! PATH="$BIN:$PATH" IMAGE_TAG=m7 ENV_FILE="$WORK/env" bash "$DEPLOY" > "$FAKE_DIR/out.log" 2>&1; then
  fail "deploy-order: deploy.sh failed: $(tail -5 "$FAKE_DIR/out.log" | tr '\n' '|')"
fi
PULL_LINE=$(grep -n "^docker pull $REPO:m7\$" "$FAKE_DIR/calls.log" | head -1 | cut -d: -f1)
LAST_RMI=$(grep -n '^docker rmi ' "$FAKE_DIR/calls.log" | tail -1 | cut -d: -f1)
[ -n "$PULL_LINE" ] || fail "deploy-order: deploy.sh never pulled $REPO:m7"
if [ -z "$LAST_RMI" ]; then
  fail "deploy-order: no old $REPO tag was removed before the pull"
elif [ -n "$PULL_LINE" ] && [ "$LAST_RMI" -gt "$PULL_LINE" ]; then
  fail "deploy-order: old tags were removed after the pull (line $LAST_RMI > $PULL_LINE)"
fi
expect_removed deploy-order "$REPO:m1 $REPO:m2 $REPO:m3"
grep -q '^docker image prune' "$FAKE_DIR/calls.log" && fail "deploy-order: deploy.sh still calls the global docker image prune"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-prune: $CASES cases passed"
