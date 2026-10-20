#!/usr/bin/env bash
#
# Tests for scripts/deploy-is-tip.sh, against a throwaway bare repository (no network, no GitHub).
#
#   scripts/test_deploy_is_tip.sh
#
# Both directions: the tip of main deploys, an older commit of main does not. And the third case, which is the
# dangerous one: when the remote cannot be asked, the script fails rather than answering either way, so a broken
# lookup can neither roll production back nor silently skip a deploy that should have run.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/deploy-is-tip.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() { echo "FAIL: $1"; FAILED=1; }

git init -q --bare "$WORK/origin.git"
git init -q "$WORK/clone"
git -C "$WORK/clone" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m older
OLDER=$(git -C "$WORK/clone" rev-parse HEAD)
git -C "$WORK/clone" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m newer
NEWER=$(git -C "$WORK/clone" rev-parse HEAD)
git -C "$WORK/clone" push -q "$WORK/origin.git" HEAD:refs/heads/main

# the tip of main: deploys
OUT="$WORK/out-tip"; : > "$OUT"
if GITHUB_OUTPUT="$OUT" bash "$SCRIPT" "$NEWER" "$WORK/origin.git" > /dev/null; then
  grep -qx 'current=true' "$OUT" || fail "the tip of main was not marked current: $(cat "$OUT")"
else
  fail "the script failed for the tip of main"
fi

# an older commit of main (a re-run, or a deploy queued behind a newer merge): skipped, and the job stays green
OUT="$WORK/out-older"; : > "$OUT"
if GITHUB_OUTPUT="$OUT" bash "$SCRIPT" "$OLDER" "$WORK/origin.git" > /dev/null; then
  grep -qx 'current=false' "$OUT" || fail "an older commit was not marked stale: $(cat "$OUT")"
else
  fail "the script failed for an older commit instead of skipping it"
fi

# the remote cannot be asked: no answer at all, and a non-zero exit
OUT="$WORK/out-broken"; : > "$OUT"
if GITHUB_OUTPUT="$OUT" bash "$SCRIPT" "$NEWER" "$WORK/missing.git" > /dev/null 2>&1; then
  fail "the script succeeded although the remote could not be read"
fi
grep -q 'current=' "$OUT" && fail "the script answered although the remote could not be read: $(cat "$OUT")"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-is-tip: 3 cases passed"
