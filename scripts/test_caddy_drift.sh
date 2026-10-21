#!/usr/bin/env bash
#
# Tests for scripts/caddy-drift.sh (plan W1c): the medioo/baymax site blocks of the box's Caddyfile against
# docs/infra/Caddyfile.medioo. Both directions, against fixture files built from the real repo file:
# - identical blocks pass, whatever surrounds them (the n8n and bddrug blocks, comments, blank lines, block order);
# - one changed line in a header, a route or the redirect fails, with the message and a diff of OUR blocks only;
# - a block missing from either side fails (an empty extraction must never compare equal to another empty one);
# - nothing from the other tenants' blocks is ever printed (the CI log is public, DR-38).
#
#   bash scripts/test_caddy_drift.sh
#   DRIFT_SH=/path/to/copy.sh bash scripts/test_caddy_drift.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIFT="${DRIFT_SH:-$ROOT/scripts/caddy-drift.sh}"
REPO_FILE="$ROOT/docs/infra/Caddyfile.medioo"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0
MSG="box Caddyfile drifted from the repo — fold the box edit in first"

fail() { echo "FAIL: $1"; FAILED=1; }

# the other tenants, as the box holds them; the marker must never reach the output
OTHERS='{
	email ops@example.invalid
}

{$DOMAIN} {
	reverse_proxy n8n:5678 {
		header_up X-Tenant-Marker OTHER-TENANT-NEVER-PRINTED
	}
}

drugdb.eliooo.org {
	header {
		X-Content-Type-Options "nosniff"
	}
	reverse_proxy bddrug-web:3000
}'

# our blocks, as the box holds them: the repo file without its comments, with the box's own indentation and spacing
ours_as_box() { grep -v '^[[:space:]]*#' "$REPO_FILE"; }

box() { # box <name> <content>: writes $WORK/<name>.Caddyfile
  printf '%s\n' "$2" > "$WORK/$1.Caddyfile"
}

run() { # run <box file>: exit code of the drift check; output in $WORK/out.log
  CASES=$((CASES + 1))
  bash "$DRIFT" "$REPO_FILE" "$1" > "$WORK/out.log" 2>&1
}
out() { tr '\n' '|' < "$WORK/out.log" | cut -c1-500; }
no_leak() { grep -q 'OTHER-TENANT-NEVER-PRINTED' "$WORK/out.log" && fail "$1: another tenant's block reached the output"; }

# sanity: the repo file has both blocks, so the cases below compare something
grep -qE '^medioo\.eliooo\.org \{' "$REPO_FILE" || { echo "FAIL: $REPO_FILE has no medioo.eliooo.org block"; exit 1; }
grep -qE '^baymax\.eliooo\.org \{' "$REPO_FILE" || { echo "FAIL: $REPO_FILE has no baymax.eliooo.org block"; exit 1; }

# 1. identical: others first, then ours
box same "$OTHERS

$(ours_as_box)"
run "$WORK/same.Caddyfile" || fail "identical: drift reported: $(out)"
no_leak identical

# 2. identical, ours first and in the other order, extra blank lines and comments of the box's own
box reordered "$(awk '/^baymax\.eliooo\.org \{/,/^\}/' "$REPO_FILE")

# a comment on the box
$(awk '/^medioo\.eliooo\.org \{/,/^\}/' "$REPO_FILE")


$OTHERS"
run "$WORK/reordered.Caddyfile" || fail "reordered: drift reported for the same blocks in another order: $(out)"

# 3-6. one changed line fails, with the message, a diff naming the line, and nothing of the other tenants
while IFS='|' read -r name from to; do
  [ -n "$name" ] || continue
  grep -qF -- "$from" "$REPO_FILE" || { fail "$name: the fixture's line [$from] is not in the repo file"; continue; }
  box "$name" "$OTHERS

$(ours_as_box | awk -v f="$from" -v t="$to" '{ i = index($0, f); if (i) $0 = substr($0, 1, i - 1) t substr($0, i + length(f)); print }')"
  if run "$WORK/$name.Caddyfile"; then fail "$name: exited 0 with [$from] changed to [$to]: $(out)"; continue; fi
  grep -qF "$MSG" "$WORK/out.log" || fail "$name: no '$MSG' line: $(out)"
  grep -qF -- "$to" "$WORK/out.log" || fail "$name: the diff does not show the box's line [$to]: $(out)"
  no_leak "$name"
done <<'TABLE'
header|Referrer-Policy "strict-origin-when-cross-origin"|Referrer-Policy "no-referrer"
route|reverse_proxy medioo-web:3000|reverse_proxy medscribe-ai:8086
default|reverse_proxy medscribe-ai:8086|reverse_proxy medioo-web:3000
redirect|redir https://medioo.eliooo.org{uri} permanent|redir https://medioo.eliooo.org{uri} temporary
TABLE

# 7. a line added on the box only (an edit made there and never folded in)
box added "$OTHERS

$(ours_as_box | awk '{print} /^[[:space:]]*-Server/ {print "\t\tX-Box-Only \"1\""}')"
if run "$WORK/added.Caddyfile"; then fail "added: exited 0 with a header line only the box has: $(out)"; fi
grep -qF 'X-Box-Only' "$WORK/out.log" || fail "added: the diff does not show the box-only line: $(out)"

# 8. our medioo block missing from the box: fails, never "both empty, so equal"
box missing "$OTHERS

$(awk '/^baymax\.eliooo\.org \{/,/^\}/' "$REPO_FILE")"
if run "$WORK/missing.Caddyfile"; then fail "missing: exited 0 with no medioo.eliooo.org block on the box: $(out)"; fi
grep -qF 'no medioo.eliooo.org block' "$WORK/out.log" || fail "missing: no 'no medioo.eliooo.org block' line: $(out)"
no_leak missing

# 9. a box file holding only the other tenants (or an empty read): fails on both blocks
box empty ""
if run "$WORK/empty.Caddyfile"; then fail "empty: exited 0 for an empty box Caddyfile: $(out)"; fi

# 10. two medioo blocks on the box (a paste appended instead of replaced): which one Caddy uses is not ours to guess
box twice "$OTHERS

$(ours_as_box)

$(awk '/^medioo\.eliooo\.org \{/,/^\}/' "$REPO_FILE")"
if run "$WORK/twice.Caddyfile"; then fail "twice: exited 0 with two medioo.eliooo.org blocks on the box: $(out)"; fi

# 11. unreadable box file
CASES=$((CASES + 1))
if bash "$DRIFT" "$REPO_FILE" "$WORK/does-not-exist" > "$WORK/out.log" 2>&1; then fail "unreadable: exited 0: $(out)"; fi

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "caddy-drift: $CASES cases passed"
