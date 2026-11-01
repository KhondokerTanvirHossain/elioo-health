#!/usr/bin/env bash
#
# Tests for scripts/check-runner-labels.sh, against throwaway workflow dirs (no network, no GitHub).
#
#   scripts/test_check_runner_labels.sh
#
# Both directions: one explicit label on every job passes (a trailing comment and quotes ignored); the 2026-11-01
# shape (the deploy job back on ubuntu-latest), two explicit labels that differ, a label moved in a second workflow
# file, an expression or a list, and a dir with no workflow or no runs-on at all are each refused, by the rule that
# fired. Last, the repo's own workflows pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/check-runner-labels.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() { echo "FAIL: $1"; FAILED=1; }
check() { bash "$SCRIPT" "$WORK/$1" > "$WORK/out" 2>&1; }
# workflow <dir> <file> <build label line> <deploy label line>
workflow() {
  mkdir -p "$WORK/$1"
  cat > "$WORK/$1/$2" <<EOF
jobs:
  build:
    name: Build and test
    runs-on: $3
    steps:
      - run: echo build
  deploy:
    needs: build
    runs-on: $4
    steps:
      - run: echo deploy
EOF
}
refused() { # <case> <dir> <text the refusal must carry>
  if check "$2"; then
    fail "$1 was accepted: $(cat "$WORK/out")"
  elif ! grep -qF -- "$3" "$WORK/out"; then
    fail "$1 was refused, but not by the rule naming '$3': $(cat "$WORK/out")"
  fi
}

# one explicit label on both jobs, one with a trailing comment and one quoted: passes
workflow same ci.yml 'ubuntu-26.04' '"ubuntu-26.04" # the build job'"'"'s label'
check same || fail "one label on both jobs was refused: $(cat "$WORK/out")"
grep -qF 'runs-on: ubuntu-26.04 for all 2 jobs' "$WORK/out" || fail "the pass did not name the label and count: $(cat "$WORK/out")"

# 2026-11-01: the deploy job, which no PR runs, back on ubuntu-latest: refused, naming the label and its line
workflow latest ci.yml 'ubuntu-26.04' 'ubuntu-latest'
refused "a deploy job on ubuntu-latest" latest "ci.yml:9: runs-on 'ubuntu-latest' moves under us"
grep -qF "found 2: ubuntu-26.04 ubuntu-latest" "$WORK/out" || fail "the split was not named: $(cat "$WORK/out")"

# both jobs on ubuntu-latest: one distinct label, still refused for moving
workflow bothlatest ci.yml 'ubuntu-latest' 'ubuntu-latest'
refused "both jobs on ubuntu-latest" bothlatest "runs-on 'ubuntu-latest' moves under us"

# two explicit labels that differ (one job's label moved without the other's): refused
workflow split ci.yml 'ubuntu-26.04' 'ubuntu-24.04'
refused "two different explicit labels" split "runs-on must be one label shared by every job; found 2: ubuntu-24.04 ubuntu-26.04"

# a second workflow file on another label: every file is read, so refused
workflow twofiles ci.yml 'ubuntu-26.04' 'ubuntu-26.04'
workflow twofiles other.yaml 'ubuntu-24.04' 'ubuntu-24.04'
refused "a second workflow on another label" twofiles "found 2: ubuntu-24.04 ubuntu-26.04"

# an expression or a list is not one plain label: refused
workflow expr ci.yml '${{ matrix.os }}' '${{ matrix.os }}'
refused "an expression label" expr "is not one plain label"
workflow list ci.yml '[self-hosted, linux]' '[self-hosted, linux]'
refused "a list label" list "is not one plain label"

# nothing to read: refused rather than passing vacuously
mkdir -p "$WORK/empty"
refused "a dir with no workflow" empty "no workflow file in"
mkdir -p "$WORK/norunson"
printf 'jobs:\n  build:\n    steps:\n      - run: echo\n' > "$WORK/norunson/ci.yml"
refused "a workflow with no runs-on" norunson "no runs-on in"

# the repo's own workflows
bash "$SCRIPT" "$ROOT/.github/workflows" > "$WORK/out" 2>&1 || fail "the repo's workflows were refused: $(cat "$WORK/out")"

if [ "$FAILED" -eq 0 ]; then
  echo "check-runner-labels: all cases passed"
fi
exit "$FAILED"
