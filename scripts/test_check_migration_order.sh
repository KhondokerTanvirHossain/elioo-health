#!/usr/bin/env bash
#
# Tests for scripts/check-migration-order.sh, against a throwaway repository (no network, no GitHub).
#
#   scripts/test_check_migration_order.sh
#
# Both directions: a branch adding the next number passes; a branch adding a number main already reached fails,
# whether main took that number from another branch (the D2/D4b/D1 collision) or the branch repeats one. A branch
# that renumbers its migration above main passes again, and a base that cannot be resolved fails rather than passing.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-migration-order.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
DIR=db/migration

fail() { echo "FAIL: $1"; FAILED=1; }
git_() { git -C "$WORK/repo" -c user.name=t -c user.email=t@example.invalid "$@"; }
add() { mkdir -p "$WORK/repo/$DIR"; echo "-- $1" > "$WORK/repo/$DIR/$1"; git_ add "$DIR/$1"; git_ commit -q -m "$1"; }
check() { (cd "$WORK/repo" && bash "$SCRIPT" "$1" "$DIR" > "$WORK/out" 2>&1); }

git init -q "$WORK/repo"
git_ checkout -q -b main
add V1__init.sql
add V2__second.sql

# a branch adding the next number: passes
git_ checkout -q -b next
add V3__mine.sql
check main || fail "the next number was refused: $(cat "$WORK/out")"

# main takes V3 from another branch first (the sibling PR merged): this branch's V3 is now refused, by name
git_ checkout -q main
add V3__theirs.sql
git_ checkout -q next
if check main; then
  fail "a V3 already on main was accepted: $(cat "$WORK/out")"
fi
grep -q 'V3__mine.sql takes V3, but main already reaches V3' "$WORK/out" || fail "the refusal did not name the file: $(cat "$WORK/out")"

# a branch repeating a number well below main's highest: refused
git_ checkout -q -b repeat main
add V2__again.sql
if check main; then
  fail "a repeated V2 was accepted: $(cat "$WORK/out")"
fi

# the collision fixed the way the plan says: renamed to max+1 on the branch (a rename, not a new file): passes
git_ checkout -q next
git_ mv "$DIR/V3__mine.sql" "$DIR/V4__mine.sql"
git_ commit -q -m renumber
check main || fail "the renumbered V4 was refused: $(cat "$WORK/out")"

# a commit that adds no migration (main itself, or a branch with none): passes
git_ checkout -q main
check main || fail "main against itself was refused: $(cat "$WORK/out")"

# the base cannot be resolved: fails rather than passing unseen
if check refs/remotes/origin/nowhere; then
  fail "an unresolvable base passed"
fi

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "check-migration-order: 6 cases passed"
