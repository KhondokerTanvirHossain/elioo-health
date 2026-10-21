#!/usr/bin/env bash
#
# Has the box's Caddyfile drifted from the repo? (DR-40 A1, plan W1c)
#
#   scripts/caddy-drift.sh <repo Caddyfile.medioo> <box Caddyfile>
#   e.g. on the box: bash caddy-drift.sh Caddyfile.medioo /home/ec2-user/n8n/Caddyfile
#
# Compares our two site blocks, medioo.eliooo.org and baymax.eliooo.org, in each file: comments, blank lines,
# indentation and the order of the blocks aside, every line must be the same. Exits 0 when they are; 1 with
# "box Caddyfile drifted from the repo — fold the box edit in first" and a diff when they are not; 1 when either
# file lacks a block or holds it twice (two empty extractions must never compare equal). Reads both files, writes
# nothing. Prints only our blocks' lines, never the other tenants' (n8n, bddrug): the CI log is public (DR-38).
# Tested both ways by scripts/test_caddy_drift.sh.
set -uo pipefail

REPO_FILE="${1:?usage: caddy-drift.sh <repo Caddyfile.medioo> <box Caddyfile>}"
BOX_FILE="${2:?usage: caddy-drift.sh <repo Caddyfile.medioo> <box Caddyfile>}"
SITES="medioo.eliooo.org baymax.eliooo.org"
MSG="box Caddyfile drifted from the repo — fold the box edit in first"

for f in "$REPO_FILE" "$BOX_FILE"; do
  [ -r "$f" ] || { echo "caddy-drift: cannot read $f"; exit 1; }
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# site_block <file> <host>: the block that opens with exactly "<host> {" on its own line, through its matching
# closing brace (counted, so nested blocks and {placeholders} are followed), with full-line comments and blank lines
# dropped and each line trimmed. A first line "#blocks <n>" says how many such blocks the file holds.
site_block() {
  awk -v host="$2" '
    function trim(s) { sub(/^[ \t\r]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
    {
      line = trim($0)
      if (depth == 0) {
        if (line == host " {" || line == host "{") { n++; depth = 1; if (n == 1) out = out line "\n"; next }
        next
      }
      if (line == "" || substr(line, 1, 1) == "#") next
      opens = gsub(/\{/, "{", line); closes = gsub(/\}/, "}", line)
      depth += opens - closes
      if (n == 1) out = out line "\n"
    }
    END { printf "#blocks %d\n%s", n, out }' "$1"
}

status=0
for site in $SITES; do
  for side in repo box; do
    if [ "$side" = repo ]; then f="$REPO_FILE"; else f="$BOX_FILE"; fi
    site_block "$f" "$site" > "$TMP/$side.$site"
    n=$(head -1 "$TMP/$side.$site" | awk '{print $2}')
    if [ "$n" != 1 ]; then
      if [ "$n" = 0 ]; then echo "caddy-drift: no $site block in the $side file ($f)"
      else echo "caddy-drift: $n $site blocks in the $side file ($f); expected exactly one"; fi
      status=1
    fi
  done
done
[ "$status" = 0 ] || { echo "$MSG"; exit 1; }

for side in repo box; do
  for site in $SITES; do tail -n +2 "$TMP/$side.$site"; done > "$TMP/$side"
done

if ! diff -u --label "repo $(basename "$REPO_FILE")" --label "box $(basename "$BOX_FILE")" "$TMP/repo" "$TMP/box" > "$TMP/diff"; then
  echo "$MSG"
  cat "$TMP/diff"
  exit 1
fi
echo "caddy-drift: $SITES match the repo ($(wc -l < "$TMP/repo" | tr -d ' ') lines)"
