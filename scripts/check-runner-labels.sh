#!/usr/bin/env bash
#
# Do all the workflow's jobs run on one explicitly pinned runner image?
#
#   scripts/check-runner-labels.sh [workflow dir]
#
# The dir defaults to .github/workflows. Reads every `runs-on:` of every *.yml / *.yaml there and exits non-zero,
# naming the labels, when there is not exactly one distinct label, when a label is a moving one (`*-latest`), when a
# `runs-on:` is not a plain label (an expression, a list, a group), and when it finds no workflow file or no
# `runs-on:` at all (a check that finds nothing to read must not pass).
#
# WHY THIS EXISTS. On 2026-11-01 ubuntu-latest moved main's runs to ubuntu-26.04 (Docker API 1.54) while the PR runs
# of the same commit had built on ubuntu-24.04 (API 1.48): the PR was green and main went red in every Testcontainers
# class (run 41288228021). A PR check on another image than main's cannot fail for main's reason. The deploy job runs
# only on main, so a label moved on it alone would show up only there; this runs in the build job on every PR.
# Its cases: scripts/test_check_runner_labels.sh.
set -uo pipefail

DIR="${1:-.github/workflows}"

FILES=$(find "$DIR" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | sort)
if [ -z "$FILES" ]; then
  echo "::error::no workflow file in $DIR: nothing to check, refusing to pass"
  exit 1
fi

# file:line<TAB>label, one per runs-on; the label with its comment, quotes and surrounding space removed
# shellcheck disable=SC2086
FOUND=$(awk '
  /^[[:space:]]*runs-on:/ {
    v = $0
    sub(/^[[:space:]]*runs-on:/, "", v)
    sub(/[[:space:]]#.*$/, "", v)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
    gsub(/["\047]/, "", v)
    printf "%s:%d\t%s\n", FILENAME, FNR, v
  }' $FILES)

if [ -z "$FOUND" ]; then
  echo "::error::no runs-on in $DIR: nothing to check, refusing to pass"
  exit 1
fi

FAILED=0
while IFS="$(printf '\t')" read -r WHERE LABEL; do
  case "$LABEL" in
    '' | *'['* | *'{'* | *'$'* | *' '* )
      echo "::error::$WHERE: runs-on '$LABEL' is not one plain label (write the image name itself, e.g. ubuntu-26.04)"
      FAILED=1 ;;
    *latest*)
      echo "::error::$WHERE: runs-on '$LABEL' moves under us; pin an explicit image (never *-latest)"
      FAILED=1 ;;
  esac
done <<EOF
$FOUND
EOF

DISTINCT=$(printf '%s\n' "$FOUND" | cut -f2 | sort -u)
COUNT=$(printf '%s\n' "$DISTINCT" | wc -l | tr -d ' ')
if [ "$COUNT" -ne 1 ]; then
  echo "::error::runs-on must be one label shared by every job; found $COUNT: $(printf '%s' "$DISTINCT" | tr '\n' ' ')"
  printf '%s\n' "$FOUND" | sed 's/^/  /'
  FAILED=1
fi

if [ "$FAILED" -eq 0 ]; then
  echo "runs-on: $(printf '%s' "$DISTINCT") for all $(printf '%s\n' "$FOUND" | wc -l | tr -d ' ') jobs"
fi
exit "$FAILED"
