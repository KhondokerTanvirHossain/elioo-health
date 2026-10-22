#!/usr/bin/env bash
#
# Refuse any way for medioo-web's server to mutate Java (DR-40 A3).
#
#   scripts/check-no-server-mutation.sh [dir]        # default: medioo-web/src
#
# WHY. Next talks to Java server-side with GET and HEAD only; every mutation goes browser -> Caddy -> Java. A fetch
# from Node sends no Origin header, so a server-side POST would pass Java's Origin check by construction rather than
# by being same-origin. This is the medioo-web half of "enforced twice"; Java's 403 FORBIDDEN_ORIGIN on a missing
# Origin is the other half.
#
# It fails on:
#   1. a server action: the directive 'use server' or "use server", anywhere;
#   2. a mutating route handler: an exported POST, PUT, PATCH or DELETE (function or const);
#   3. in any file that names JAVA_INTERNAL_URL, a `method` that is not the literal GET or HEAD — including a method
#      held in a variable, which cannot be shown to be safe;
#   4. a scan of zero files: a guard that read nothing passes vacuously, which is the same defect one level up;
#   5. any file but api/java.ts (relative to the scanned dir) that names JAVA_INTERNAL_URL: every Java read lives in
#      that one module (plan W3), so rule 3 sees every method Next sends Java. A unit test (*.test.ts[x]) may name it
#      to stub it; it runs in Vitest, never in the server.
#
# Rule 3 is a heuristic over the file that names the URL; rule 5 makes that file the only one.
#
# No mapfile/readarray and no grep -P: macOS ships bash 3.2 and BSD grep, and a guard that only runs on the CI runner
# is a guard that lets a contributor commit the thing locally and find out later.
set -uo pipefail

DIR="${1:-$(dirname "$0")/../medioo-web/src}"
if [ ! -d "$DIR" ]; then
  echo "FAIL: $DIR is not a directory" >&2
  exit 1
fi

FILES=$(find "$DIR" -type f \( -name '*.ts' -o -name '*.tsx' -o -name '*.mts' -o -name '*.cts' \
  -o -name '*.js' -o -name '*.jsx' -o -name '*.mjs' -o -name '*.cjs' \) | sort)
count=$(printf '%s\n' "$FILES" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  echo "FAIL: scanned zero source files under $DIR — a check that read nothing proves nothing" >&2
  exit 1
fi

violations=""
add() { violations="${violations}$1"$'\n'; }

while IFS= read -r f; do
  [ -z "$f" ] && continue
  while IFS= read -r hit; do
    [ -n "$hit" ] && add "$f:$hit: server action ('use server') — mutations go browser -> Caddy -> Java (DR-40 A3)"
  done < <(grep -nE "[\"']use server[\"']" "$f" | cut -d: -f1)
  while IFS= read -r hit; do
    [ -n "$hit" ] && add "$f:$hit: mutating route handler (POST/PUT/PATCH/DELETE) — Next serves GET only (DR-40 A3)"
  done < <(grep -nE 'export[[:space:]]+((async[[:space:]]+)?function|const|let|var)[[:space:]]+(POST|PUT|PATCH|DELETE)([^A-Za-z0-9_$]|$)' "$f" | cut -d: -f1)
  if grep -q 'JAVA_INTERNAL_URL' "$f"; then
    rel="${f#"$DIR"/}"
    # A unit test (Vitest, never the server) may name the variable to stub it, as api/java.test.ts does.
    if [ "$rel" != "api/java.ts" ] && [[ "$rel" != *.test.ts ]] && [[ "$rel" != *.test.tsx ]]; then
      add "$f:$(grep -n 'JAVA_INTERNAL_URL' "$f" | head -1 | cut -d: -f1): names JAVA_INTERNAL_URL outside api/java.ts — every Java read goes through that one module (rule 5)"
    fi
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      n=${line%%:*}
      text=${line#*:}
      if ! printf '%s\n' "$text" | grep -qiE "method[[:space:]]*:[[:space:]]*[\"'\`](GET|HEAD)[\"'\`]"; then
        add "$f:$n: a fetch to JAVA_INTERNAL_URL with a method other than a literal GET/HEAD (DR-40 A3)"
      fi
    done < <(grep -nE 'method[[:space:]]*[:=]' "$f")
  fi
done <<< "$FILES"

if [ -n "$violations" ]; then
  printf 'FAIL: medioo-web may not mutate from the server:\n%s' "$violations" >&2
  exit 1
fi
echo "OK: $count source files under $DIR; no server action, no mutating route handler, Java fetched with GET/HEAD only"
