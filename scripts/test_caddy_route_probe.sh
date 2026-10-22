#!/usr/bin/env bash
#
# Tests for scripts/caddy-route-probe.sh (plan Wave 3, Task F0): the repo's Caddyfile.medioo, run in a throwaway Caddy
# of the box's version with each upstream replaced by a fixed answer, asked every row of scripts/smoke-https.sh. Real
# Caddy, real curl, no network beyond 127.0.0.1 (and the Caddy image pull). Needs docker.
#
#   bash scripts/test_caddy_route_probe.sh
#   PROBE_SH=/path/to/copy.sh bash scripts/test_caddy_route_probe.sh   # replays the same cases against another copy
#
# What is under test, in both directions:
# - the repo's file and table pass, every row, and the row count is the table's less its {old} rows (a probe that asks
#   nothing cannot pass);
# - theProbeSpeaksPlainHttp: /app/healthz answers 298 (medioo-web's stand-in), never a 308 to https, so the site
#   address was rewritten and Caddy's automatic HTTPS is off;
# - a file that routes /app/api/* to Next fails on the /app/api/me row, and only that row (Java owns the API);
# - a file whose preview matcher is `path /app/preview/*` alone fails on the /app/preview row, and only that row
#   (`/app/preview/*` does not match `/app/preview`);
# - the other direction, a matcher that reaches too far: `path /app/preview*` fails on /app/previewx alone,
#   `handle /_next/* {…}` on /_next/image alone, and the preview matcher without its `not path` line on
#   /app/preview/probe alone (the budget probe never runs the preview gate, so it must stay off the preview);
# - a file missing an asset route fails on that asset's row;
# - a table with no rows, a file with no medioo.eliooo.org block, and a file with an upstream the probe does not know
#   each fail, rather than pass vacuously or send a row somewhere unreplaced.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE="${PROBE_SH:-$ROOT/scripts/caddy-route-probe.sh}"
CADDYFILE="$ROOT/docs/infra/Caddyfile.medioo"
SMOKE="$ROOT/scripts/smoke-https.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0
# the smoke table's rows less the {old} one: /app/healthz /actuator/health /app/api/me /app/ / /medscribeai
# /medscribeai/ /baymax /baymax/ webhook /_next/static/x.js /fonts/noto-sans-latin.woff2 /brand/mio-head.webp
# /app/preview /app/preview/ /app/previewx /_next/image /app/preview/probe
ROWS=18

fail() { echo "FAIL: $1"; FAILED=1; }

# probe <caddyfile> [smoke]: runs the probe, output to $WORK/out.log; returns its exit code
probe() {
  CASES=$((CASES + 1))
  bash "$PROBE" "$1" "${2:-$SMOKE}" > "$WORK/out.log" 2>&1
}
out() { tr '\n' '|' < "$WORK/out.log" | cut -c1-700; }
fails() { grep -c '^FAIL ' "$WORK/out.log"; }
passes() { grep -c '^PASS ' "$WORK/out.log"; }

# variant <name> <perl expression>: the repo file with one edit applied; the case is void if the edit matched nothing
variant() {
  perl -0pe "$2" "$CADDYFILE" > "$WORK/$1.Caddyfile"
  if cmp -s "$CADDYFILE" "$WORK/$1.Caddyfile"; then
    fail "$1: the edit changed nothing in Caddyfile.medioo, so the case would test the repo file again"
    return 1
  fi
}

# 1. the repo's file and table: every row passes, and there are exactly ROWS of them
if probe "$CADDYFILE"; then :; else fail "repo: exited non-zero on the repo's file: $(out)"; fi
[ "$(passes)" = "$ROWS" ] || fail "repo: $(passes) PASS rows, expected $ROWS: $(out)"
[ "$(fails)" = 0 ] || fail "repo: $(fails) FAIL rows: $(out)"
grep -q '^PASS /app/api/me 299 (java)$' "$WORK/out.log" || fail "repo: no 'PASS /app/api/me 299 (java)' line: $(out)"
grep -q '^PASS /app/preview 298 (next)$' "$WORK/out.log" || fail "repo: no 'PASS /app/preview 298 (next)' line: $(out)"
# the probe prints each row's spec as written, so an {old} row it asked would print the literal {old}
grep -qF '{old}' "$WORK/out.log" && fail "repo: an {old} row was asked of the probe: $(grep -F '{old}' "$WORK/out.log" | head -3 | tr '\n' '|')"

# 2. theProbeSpeaksPlainHttp: the request to /app/healthz reached Next's stand-in, not Caddy's http->https redirect
grep -q '^PASS /app/healthz 298 (next)$' "$WORK/out.log" || fail "theProbeSpeaksPlainHttp: no 'PASS /app/healthz 298 (next)' line: $(out)"
grep -qE ' (got )?308( |$)' "$WORK/out.log" && fail "theProbeSpeaksPlainHttp: a row was answered 308: $(out)"

# 3. one wrong route at a time: <name>|<perl edit>|<the one FAIL line, exactly>
while IFS='|' read -r name edit line; do
  [ -n "$name" ] || continue
  variant "$name" "$edit" || continue
  if probe "$WORK/$name.Caddyfile"; then fail "$name: exited 0: $(out)"; continue; fi
  grep -qxF -- "$line" "$WORK/out.log" || fail "$name: no '$line' line: $(out)"
  [ "$(fails)" = 1 ] || fail "$name: $(fails) FAIL rows, expected exactly 1: $(out)"
done <<'TABLE'
api-to-next|s/(\n\thandle \{\n)/\n\thandle \/app\/api\/* {\n\t\treverse_proxy medioo-web:3000\n\t}\n$1/|FAIL /app/api/me got 298 (next), expected 299 (java)
preview-star-only|s/\n\t\tpath \/app\/preview \/app\/preview\/\*\n/\n\t\tpath \/app\/preview\/*\n/|FAIL /app/preview got 299 (java), expected 298 (next)
preview-prefix|s/\n\t\tpath \/app\/preview \/app\/preview\/\*\n/\n\t\tpath \/app\/preview*\n/|FAIL /app/previewx got 298 (next), expected 299 (java)
next-wide|s/\thandle \/_next\/static\/\* \{/\thandle \/_next\/* {/|FAIL /_next/image got 298 (next), expected 299 (java)
probe-exposed|s/\n\t\tnot path \/app\/preview\/probe [^\n]*\n/\n/|FAIL /app/preview/probe got 298 (next), expected 299 (java)
no-fonts|s/\thandle \/fonts\/\* \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /fonts/noto-sans-latin.woff2 got 299 (java), expected 298 (next)
TABLE

# 4. a table with no rows: refused, not passed
printf '#!/usr/bin/env bash\nTABLE=%s%s\n' "'" "'" > "$WORK/empty-smoke.sh"
if probe "$CADDYFILE" "$WORK/empty-smoke.sh"; then fail "no rows: exited 0 on an empty table: $(out)"; fi
grep -qF 'read no rows' "$WORK/out.log" || fail "no rows: the output does not say it read no rows: $(out)"

# 5. no medioo.eliooo.org block: refused
printf 'baymax.eliooo.org {\n\tredir https://medioo.eliooo.org{uri} permanent\n}\n' > "$WORK/no-site.Caddyfile"
if probe "$WORK/no-site.Caddyfile"; then fail "no site: exited 0: $(out)"; fi
grep -qF 'no medioo.eliooo.org block' "$WORK/out.log" || fail "no site: the output does not name the missing block: $(out)"

# 6. an upstream the probe does not replace: refused before Caddy starts, never sent anywhere
variant unknown-upstream 's/reverse_proxy medscribe-ai:8086/reverse_proxy n8n:5678/' || true
if [ -f "$WORK/unknown-upstream.Caddyfile" ]; then
  if probe "$WORK/unknown-upstream.Caddyfile"; then fail "unknown upstream: exited 0: $(out)"; fi
  grep -qF 'unknown upstream' "$WORK/out.log" || fail "unknown upstream: the output does not say so: $(out)"
fi

# 7. no arguments: usage, exit 2
CASES=$((CASES + 1))
bash "$PROBE" > "$WORK/out.log" 2>&1; rc=$?
[ "$rc" = 2 ] || fail "usage: exit $rc with no arguments, expected 2: $(out)"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "caddy-route-probe: $CASES cases passed"
