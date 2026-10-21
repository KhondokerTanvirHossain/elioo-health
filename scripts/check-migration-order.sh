#!/usr/bin/env bash
#
# Does every migration this commit adds come after everything already on main?
#
#   scripts/check-migration-order.sh [base ref] [migration dir ...]
#
# base ref defaults to origin/main; the dirs default to the Baymax and MedScribe Flyway locations. Exits non-zero,
# naming each file, when HEAD adds a versioned migration whose number is not above the highest number in that dir on
# the base ref, and when the base ref cannot be resolved (a check that cannot look must not pass).
#
# WHY THIS EXISTS. Both Flyway instances run with outOfOrder=false, and every merge to main deploys. Two branches that
# each take "the next free number" when written collide: whichever merges second either repeats a version Flyway
# then refuses at startup ("more than one migration with version"), or sits below one already applied in production,
# which Flyway's validate refuses on the next startup. Either way the deployable does not start on the shared box.
# Numbers are taken at merge time (the Wave 2b/3 plan's Global Constraints); this makes the required check say so on
# the PR instead of the deploy finding out. BaymaxMigrationSequenceTest checks the tree itself (no gap, no repeat).
set -uo pipefail

BASE="${1:-origin/main}"
shift || true
if [ "$#" -eq 0 ]; then
  set -- baymax/src/main/resources/db/baymax/migration medscribe-ai/src/main/resources/db/migration
fi

if ! git rev-parse --verify -q "$BASE^{commit}" > /dev/null; then
  echo "::error::cannot resolve $BASE; fetch it first (git fetch --depth=1 origin +refs/heads/main:refs/remotes/origin/main)"
  exit 1
fi

version() { sed -n 's|^\(.*/\)\{0,1\}V\([0-9][0-9]*\)__.*\.sql$|\2|p'; }

FAILED=0
for DIR in "$@"; do
  HIGHEST=$(git ls-tree --name-only "$BASE" -- "$DIR/" | version | sort -n | tail -1)
  HIGHEST=${HIGHEST:-0}
  # --no-renames: a file git would pair with one on the base by content is still listed as added at its own number
  while IFS= read -r FILE; do
    [ -z "$FILE" ] && continue
    V=$(printf '%s\n' "$FILE" | version)
    [ -z "$V" ] && continue
    if [ "$V" -le "$HIGHEST" ]; then
      echo "::error file=$FILE::$FILE takes V$V, but $BASE already reaches V$HIGHEST in $DIR: renumber it to V$((HIGHEST + 1))"
      FAILED=1
    else
      echo "$FILE: V$V is above $BASE's V$HIGHEST"
    fi
  done < <(git diff --no-renames --name-only --diff-filter=A "$BASE" HEAD -- "$DIR/")
done

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "migration order: every added migration is above $BASE"
