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
# file lacks a block or holds it twice (two empty extractions must never compare equal); 1, with no diff, when a
# block never closes. Reads both files, writes nothing. Prints only our blocks' lines, never the other tenants' (n8n,
# bddrug): the CI log is public (DR-38). That is why an unterminated block is refused rather than diffed: its
# extraction would run on into whatever follows it, and the diff would print the other tenants' lines.
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
# closing brace, with full-line comments and blank lines dropped and each line trimmed. A first line "#blocks <n>"
# says how many such blocks the file holds; "#blocks -1", and no lines at all, says the first one never closes.
# Braces are counted as Caddy reads them: not inside a "quoted" or `backtick` token, and not in a trailing # comment,
# so `header X-Note "a { brace"` is one line of the block, not the start of a nested one; {placeholders} balance.
# A line at the block's top level that opens another site ("host.name {", "{$VAR} {", ":port {", "https://x {")
# means our block lost its closing brace. That, or reaching the end of the file still inside it, is unterminated.
site_block() {
  awk -v host="$2" '
    function trim(s) { sub(/^[ \t\r]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
    function bare(s) { gsub(/"([^"\\]|\\.)*"/, "", s); gsub(/`[^`]*`/, "", s); sub(/(^|[ \t])#.*$/, "", s); return s }
    {
      if (bad) next
      line = trim($0)
      if (depth == 0) {
        if (line == host " {" || line == host "{") { n++; depth = 1; if (n == 1) out = out line "\n"; next }
        next
      }
      if (line == "" || substr(line, 1, 1) == "#") next
      if (depth == 1 && line ~ /^([{][$][A-Za-z_][A-Za-z0-9_]*[}]|[A-Za-z0-9*_-]+([.][A-Za-z0-9*_-]+)+(:[0-9]+)?|:[0-9]+|https?:\/\/[^ ]+)([ ]*,[^{]*)?[ ]*[{]$/) { bad = 1; next }
      b = bare(line)
      opens = gsub(/[{]/, "{", b); closes = gsub(/[}]/, "}", b)
      depth += opens - closes
      if (n == 1) out = out line "\n"
    }
    END { if (bad || depth != 0) print "#blocks -1"; else printf "#blocks %d\n%s", n, out }' "$1"
}

status=0
for site in $SITES; do
  for side in repo box; do
    if [ "$side" = repo ]; then f="$REPO_FILE"; else f="$BOX_FILE"; fi
    site_block "$f" "$site" > "$TMP/$side.$site"
    n=$(head -1 "$TMP/$side.$site" | awk '{print $2}')
    if [ "$n" != 1 ]; then
      if ! [[ "$n" =~ ^-?[0-9]+$ ]]; then echo "caddy-drift: could not extract the $site block from the $side file ($f)"
      elif [ "$n" = 0 ]; then echo "caddy-drift: no $site block in the $side file ($f)"
      elif [ "$n" = -1 ]; then echo "caddy-drift: unterminated $site block in the $side file ($f); not diffed, so nothing after it is printed"
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
