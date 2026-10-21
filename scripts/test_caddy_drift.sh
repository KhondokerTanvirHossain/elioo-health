#!/usr/bin/env bash
#
# Tests for scripts/caddy-drift.sh (plan W1c): the medioo/baymax site blocks of the box's Caddyfile against
# docs/infra/Caddyfile.medioo. Both directions, against fixture files built from the real repo file:
# - identical blocks pass, whatever surrounds them (the n8n and bddrug blocks, comments, blank lines, block order);
# - one changed line in a header, a route or the redirect fails, with the message and a diff of OUR blocks only;
# - a block missing from either side fails (an empty extraction must never compare equal to another empty one), and
#   so does a repo file with no blocks at all against an empty box file: the one case only the count guard catches;
# - the box's own formatting is ignored: spaces for tabs, trailing whitespace, a comment and blank lines INSIDE our
#   block pass (the "ignore" direction, which identical fixtures alone never exercise);
# - a brace inside a quoted token is not a block brace, and a block that never closes (a botched hand edit) fails as
#   unterminated with no diff, instead of running the extraction on into the other tenants' blocks;
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

# our blocks, as the box holds them: the repo file without its top-level comments (case 12 re-indents and re-spaces them)
ours_as_box() { grep -v '^[[:space:]]*#' "$REPO_FILE"; }

box() { # box <name> <content>: writes $WORK/<name>.Caddyfile
  printf '%s\n' "$2" > "$WORK/$1.Caddyfile"
}

run() { # run <box file> [<repo file>]: exit code of the drift check; output in $WORK/out.log
  CASES=$((CASES + 1))
  bash "$DRIFT" "${2:-$REPO_FILE}" "$1" > "$WORK/out.log" 2>&1
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

# 12. the box's own formatting, which the check promises to ignore: spaces for tabs, trailing whitespace on every line,
#     and a comment plus extra blank lines INSIDE the medioo block. Identical fixtures never exercise this direction:
#     without the trim and the in-block filters, this is a false red drift on a correct box.
box formatting "$OTHERS

$(ours_as_box | sed 's/	/    /g' | awk '{ print $0 "  \t" } /^ *encode / { print "    # note: a comment of the box'"'"'s own"; print ""; print "   " }')"
grep -qE '^    encode .*  	$' "$WORK/formatting.Caddyfile" || fail "formatting: the fixture was not re-indented and re-spaced"
run "$WORK/formatting.Caddyfile" || fail "formatting: drift reported for the same blocks re-indented, re-spaced and commented: $(out)"
no_leak formatting

# 13. a quoted brace is a token, not a block brace (valid Caddy). With braces counted inside quotes, the medioo block
#     never closes and the extraction runs on into the n8n block AFTER it, printing its lines into the public log.
QUOTED='		X-Note "a { brace"'
box quoted "$(ours_as_box | awk -v q="$QUOTED" '{print} /^[[:space:]]*-Server/ {print q}')

$OTHERS"
if run "$WORK/quoted.Caddyfile"; then fail "quoted: exited 0 with a header line only the box has: $(out)"; fi
no_leak quoted
grep -qF 'X-Note "a { brace"' "$WORK/out.log" || fail "quoted: the diff does not show the box-only quoted line: $(out)"
# the same line on both sides is no drift at all: the quoted brace is followed, not miscounted
awk -v q="$QUOTED" '{print} /^[[:space:]]*-Server/ {print q}' "$REPO_FILE" > "$WORK/repo-quoted.medioo"
run "$WORK/quoted.Caddyfile" "$WORK/repo-quoted.medioo" || fail "quoted-both: drift reported with the same quoted line on both sides: $(out)"
no_leak quoted-both

# 14. a medioo block that never closes (a hand edit that lost its last brace), followed by the other tenants: fails as
#     unterminated, with no diff, and nothing of theirs is printed
box unterminated "$(ours_as_box | awk '/^medioo\.eliooo\.org \{/ { inm = 1 } inm && /^\}/ { inm = 0; next } {print}')

$OTHERS"
if run "$WORK/unterminated.Caddyfile"; then fail "unterminated: exited 0 with an unclosed medioo block: $(out)"; fi
grep -qF 'unterminated medioo.eliooo.org block in the box file' "$WORK/out.log" \
  || fail "unterminated: no 'unterminated medioo.eliooo.org block in the box file' line: $(out)"
grep -q '^[-+@]' "$WORK/out.log" && fail "unterminated: printed a diff of an extraction it could not bound: $(out)"
no_leak unterminated

# 15. no blocks on EITHER side (a repo file that lost them, an empty box read): two empty extractions are equal, so
#     only the per-side block count can fail this. Every other "missing" case also has a non-empty side to diff.
printf '# only a comment\n' > "$WORK/repo-empty.medioo"
box empty-both ""
if run "$WORK/empty-both.Caddyfile" "$WORK/repo-empty.medioo"; then fail "empty-both: exited 0 with no blocks on either side: $(out)"; fi
grep -qF 'no medioo.eliooo.org block in the repo file' "$WORK/out.log" \
  || fail "empty-both: no 'no medioo.eliooo.org block in the repo file' line: $(out)"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "caddy-drift: $CASES cases passed"
