#!/usr/bin/env bash
#
# Is this commit still the tip of main? Only the tip deploys.
#
#   scripts/deploy-is-tip.sh <commit sha> [remote]      (remote defaults to origin)
#
# Writes current=true or current=false to $GITHUB_OUTPUT and exits 0; exits non-zero, writing nothing, when the
# remote cannot be read, so a failed lookup neither deploys nor skips silently.
#
# WHY THIS EXISTS. The deploy job's concurrency group runs one deploy at a time, but it does not order them. A
# re-run of an older run (both journey flakes were cleared on attempt 2) finishes its build after the newer
# merge's deploy, and then deploys the older image: production and :latest roll back to code main no longer has.
# Asked inside the group, "is this still the tip?" makes the newest commit the only one that ever deploys. The
# newest commit contains every older one, so skipping an older deploy loses nothing.
set -uo pipefail

SHA="${1:?usage: deploy-is-tip.sh <commit sha> [remote]}"
REMOTE="${2:-origin}"

if ! TIP_LINE=$(git ls-remote --exit-code "$REMOTE" refs/heads/main); then
  echo "::error::could not read refs/heads/main from $REMOTE; not deploying and not skipping silently"
  exit 1
fi
TIP=${TIP_LINE%%[[:space:]]*}

if [ "$TIP" = "$SHA" ]; then
  echo "current=true" >> "$GITHUB_OUTPUT"
  echo "$SHA is the tip of main: deploying"
else
  echo "current=false" >> "$GITHUB_OUTPUT"
  echo "::notice::$SHA is no longer the tip of main ($TIP is); skipping this deploy so production never moves back"
fi
