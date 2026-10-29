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
# - F7's rule has two directions, each a variant: GET and HEAD /app/add reach Next (`add-missing` drops the block and
#   fails both rows; `add-get-only` keeps `method GET` and fails the HEAD row alone), and POST /app/add, the upload,
#   stays Java's (`add-any-method` drops the method line and fails the PROBE POST row alone); `add-wide` widens the
#   exact `path /app/add` to `/app/add*` and fails the /app/add/ boundary row alone. Each edit is anchored on the add
#   block, so another block's `method GET HEAD` (F3's sign-in matcher, F5's chat matcher) is never the one edited;
# - F3's sign-in matcher, each way it could be wrong: without `method GET HEAD` it takes POST /app/verify (S3: every
#   sign-in POST stays Java's), `path /app/verify*` takes /app/verifyx, and without the bare `/app` that row is Java's;
# - F5's rule has the same two directions as F7's: GET and HEAD /app/home reach Next (`home-missing` drops the block
#   and fails both rows; `home-get-only` keeps `method GET` and fails the HEAD row alone), and a POST stays Java's
#   (`home-any-method` drops the method line and fails the PROBE POST row alone); `home-wide` widens the exact
#   `path /app/home` to `/app/home*` and fails the /app/home/ boundary row alone. Each edit is anchored on the home
#   block, so the add and sign-in blocks' `method GET HEAD` is never the one edited;
# - F10's two blocks, each both ways. The family tab is F5's shape: `family-missing` fails GET and HEAD /app/family,
#   `family-get-only` the HEAD row alone, `family-any-method` the PROBE POST row alone, and `family-wide`
#   (`/app/family*`) the /app/family/ boundary row alone. The profile is a regexp: `profile-missing` fails GET and HEAD
#   on it, `profile-get-only` the HEAD row alone, `profile-any-method` the PROBE POST row alone (POST is Java's profile
#   save), `profile-unanchored` (the regexp without its `$`) the /profile/ boundary row alone, and `profile-any-id`
#   (`[^/]+` for the uuid) the /app/patients/x/profile boundary row alone, and `profile-patient-page` (the regexp
#   widened to `(/profile)?$`) the /app/patients/<id> boundary row alone (the patient page, still Java's). Each edit
#   is anchored on its own block;
# - F9's block is F5's shape: `health-missing` drops it and fails GET and HEAD /app/health, `health-get-only` keeps
#   `method GET` and fails the HEAD row alone, `health-any-method` drops the method line and fails the PROBE POST row
#   alone (Java routes no POST /app/health), and `health-wide` (`/app/health*`) fails the /app/health/ boundary row
#   alone. Each edit is anchored on the health block, never on /app/healthz's own `handle /app/healthz`;
# - F8's block is F5's shape: `timeline-missing` drops it and fails GET /app/timeline, GET /app/timeline?patient=<id>
#   and HEAD /app/timeline (three lines), `timeline-get-only` keeps `method GET` and fails the HEAD row alone,
#   `timeline-any-method` drops the method line and fails the PROBE POST row alone (Java routes no POST
#   /app/timeline), and `timeline-wide` (`/app/timeline*`) fails the /app/timeline/ boundary row alone. Each edit is
#   anchored on the papers block;
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
# the smoke table's rows less the {old} one: /app/healthz /actuator/health /app/api/me /app/ /app, HEAD /app/,
# /app/verify, HEAD /app/verify, /app/login /app/verifyx /app/basic /lang/en?back=..., POST /app/verify, POST /app/login,
# / /medscribeai /medscribeai/ /baymax /baymax/ webhook /_next/static/x.js /fonts/noto-sans-latin.woff2
# /brand/mio-head.webp /brand/medioo-light.svg (P3) /app/preview /app/preview/ /app/previewx /_next/image
# /app/preview/probe, F7's /app/add, /app/add/, PROBE HEAD /app/add and PROBE POST /app/add, and F5's /app/home,
# /app/home/, PROBE HEAD /app/home and PROBE POST /app/home, and F10's /app/family, /app/family/, PROBE HEAD and PROBE
# POST /app/family, /app/patients/<id>/profile, its /profile/, /app/patients/x/profile, /app/patients/<id>, and PROBE
# HEAD and PROBE POST on the profile, and F9's /app/health, /app/health/, PROBE HEAD and PROBE POST /app/health, and
# F8's /app/timeline, /app/timeline?patient=<id>, /app/timeline/, PROBE HEAD and PROBE POST /app/timeline (the probe
# sends PROBE rows; the production run never does)
ROWS=56

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
# F3: the sign-in pages are Next's for GET and HEAD, and every sign-in POST and the no-JS fallback stay Java's
for line in 'PASS /app/ 298 (next)' 'PASS /app 298 (next)' 'PASS HEAD /app/ 298 (next)' 'PASS /app/verify 298 (next)' \
  'PASS HEAD /app/verify 298 (next)' 'PASS POST /app/verify 299 (java)' 'PASS POST /app/login 299 (java)' \
  'PASS /app/basic 299 (java)' 'PASS /app/login 299 (java)' \
  'PASS /app/family 298 (next)' 'PASS PROBE POST /app/family 299 (java)' 'PASS /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile 298 (next)' \
  'PASS PROBE POST /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile 299 (java)' 'PASS /app/patients/x/profile 299 (java)' \
  'PASS /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001 299 (java)' \
  'PASS /app/health 298 (next)' 'PASS PROBE HEAD /app/health 298 (next)' 'PASS PROBE POST /app/health 299 (java)' \
  'PASS /app/health/ 299 (java)' \
  'PASS /app/timeline 298 (next)' 'PASS /app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100001 298 (next)' 'PASS PROBE HEAD /app/timeline 298 (next)' \
  'PASS PROBE POST /app/timeline 299 (java)' 'PASS /app/timeline/ 299 (java)'; do
  grep -qxF "$line" "$WORK/out.log" || fail "repo: no '$line' line: $(out)"
done
# the probe prints each row's spec as written, so an {old} row it asked would print the literal {old}
grep -qF '{old}' "$WORK/out.log" && fail "repo: an {old} row was asked of the probe: $(grep -F '{old}' "$WORK/out.log" | head -3 | tr '\n' '|')"

# 2. theProbeSpeaksPlainHttp: the request to /app/healthz reached Next's stand-in, not Caddy's http->https redirect
grep -q '^PASS /app/healthz 298 (next)$' "$WORK/out.log" || fail "theProbeSpeaksPlainHttp: no 'PASS /app/healthz 298 (next)' line: $(out)"
grep -qE ' (got )?308( |$)' "$WORK/out.log" && fail "theProbeSpeaksPlainHttp: a row was answered 308: $(out)"

# 3. one wrong route at a time: <name>|<perl edit>|<a FAIL line, exactly>[|<a second FAIL line>[|<a third>]]; the run
#    must print those lines and no other FAIL line (one, or more when the edit takes a whole block that several rows
#    ask)
while IFS='|' read -r name edit line line2 line3; do
  [ -n "$name" ] || continue
  variant "$name" "$edit" || continue
  if probe "$WORK/$name.Caddyfile"; then fail "$name: exited 0: $(out)"; continue; fi
  want=0
  for expected in "$line" "$line2" "$line3"; do
    [ -n "$expected" ] || continue
    want=$((want + 1))
    grep -qxF -- "$expected" "$WORK/out.log" || fail "$name: no '$expected' line: $(out)"
  done
  [ "$(fails)" = "$want" ] || fail "$name: $(fails) FAIL rows, expected exactly $want: $(out)"
done <<'TABLE'
api-to-next|s/(\n\thandle \{\n)/\n\thandle \/app\/api\/* {\n\t\treverse_proxy medioo-web:3000\n\t}\n$1/|FAIL /app/api/me got 298 (next), expected 299 (java)
preview-star-only|s/\n\t\tpath \/app\/preview \/app\/preview\/\*\n/\n\t\tpath \/app\/preview\/*\n/|FAIL /app/preview got 299 (java), expected 298 (next)
preview-prefix|s/\n\t\tpath \/app\/preview \/app\/preview\/\*\n/\n\t\tpath \/app\/preview*\n/|FAIL /app/previewx got 298 (next), expected 299 (java)
next-wide|s/\thandle \/_next\/static\/\* \{/\thandle \/_next\/* {/|FAIL /_next/image got 298 (next), expected 299 (java)
probe-exposed|s/\n\t\tnot path \/app\/preview\/probe [^\n]*\n/\n/|FAIL /app/preview/probe got 298 (next), expected 299 (java)
no-fonts|s/\thandle \/fonts\/\* \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /fonts/noto-sans-latin.woff2 got 299 (java), expected 298 (next)
add-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/add\n)/\n$1/|FAIL PROBE POST /app/add got 298 (next), expected 299 (java)
add-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/add\n)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/add got 299 (java), expected 298 (next)
add-wide|s/(\n\t\tmethod GET HEAD\n\t\tpath \/app\/add)\n/$1*\n/|FAIL /app/add/ got 298 (next), expected 299 (java)
add-missing|s/\t\@medioo_add \{\n\t\tmethod GET HEAD\n\t\tpath \/app\/add\n\t\}\n\thandle \@medioo_add \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/add got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/add got 299 (java), expected 298 (next)
signin-any-method|s/\n\t\tmethod GET HEAD\n\t\tpath \/app \/app\/ \/app\/verify\n/\n\t\tpath \/app \/app\/ \/app\/verify\n/|FAIL POST /app/verify got 298 (next), expected 299 (java)
signin-verify-prefix|s/\n\t\tpath \/app \/app\/ \/app\/verify\n/\n\t\tpath \/app \/app\/ \/app\/verify*\n/|FAIL /app/verifyx got 298 (next), expected 299 (java)
signin-no-bare|s/\n\t\tpath \/app \/app\/ \/app\/verify\n/\n\t\tpath \/app\/ \/app\/verify\n/|FAIL /app got 299 (java), expected 298 (next)
home-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/home\n)/\n$1/|FAIL PROBE POST /app/home got 298 (next), expected 299 (java)
home-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/home\n)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/home got 299 (java), expected 298 (next)
home-wide|s/(\n\t\tmethod GET HEAD\n\t\tpath \/app\/home)\n/$1*\n/|FAIL /app/home/ got 298 (next), expected 299 (java)
home-missing|s/\t\@medioo_chat \{\n\t\tmethod GET HEAD\n\t\tpath \/app\/home\n\t\}\n\thandle \@medioo_chat \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/home got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/home got 299 (java), expected 298 (next)
family-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/family\n)/\n$1/|FAIL PROBE POST /app/family got 298 (next), expected 299 (java)
family-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/family\n)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/family got 299 (java), expected 298 (next)
family-wide|s/(\n\t\tmethod GET HEAD\n\t\tpath \/app\/family)\n/$1*\n/|FAIL /app/family/ got 298 (next), expected 299 (java)
family-missing|s/\t\@medioo_family \{\n\t\tmethod GET HEAD\n\t\tpath \/app\/family\n\t\}\n\thandle \@medioo_family \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/family got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/family got 299 (java), expected 298 (next)
profile-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath_regexp \^\/app\/patients\/)/\n$1/|FAIL PROBE POST /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile got 298 (next), expected 299 (java)
profile-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath_regexp \^\/app\/patients\/)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile got 299 (java), expected 298 (next)
profile-unanchored|s/(\n\t\tpath_regexp \^\/app\/patients\/\[0-9a-f-\]\{36\}\/profile)\$\n/$1\n/|FAIL /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile/ got 298 (next), expected 299 (java)
profile-any-id|s/(\n\t\tpath_regexp \^\/app\/patients\/)\[0-9a-f-\]\{36\}(\/profile\$\n)/$1\[^\/\]+$2/|FAIL /app/patients/x/profile got 298 (next), expected 299 (java)
profile-patient-page|s/(\n\t\tpath_regexp \^\/app\/patients\/\[0-9a-f-\]\{36\})\/profile\$\n/$1(\/profile)?\$\n/|FAIL /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001 got 298 (next), expected 299 (java)
profile-missing|s/\t\@medioo_profile \{\n\t\tmethod GET HEAD\n\t\tpath_regexp [^\n]*\n\t\}\n\thandle \@medioo_profile \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile got 299 (java), expected 298 (next)
health-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/health\n)/\n$1/|FAIL PROBE POST /app/health got 298 (next), expected 299 (java)
health-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/health\n)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/health got 299 (java), expected 298 (next)
health-wide|s/(\n\t\tmethod GET HEAD\n\t\tpath \/app\/health)\n/$1*\n/|FAIL /app/health/ got 298 (next), expected 299 (java)
health-missing|s/\t\@medioo_health \{\n\t\tmethod GET HEAD\n\t\tpath \/app\/health\n\t\}\n\thandle \@medioo_health \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/health got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/health got 299 (java), expected 298 (next)
timeline-any-method|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/timeline\n)/\n$1/|FAIL PROBE POST /app/timeline got 298 (next), expected 299 (java)
timeline-get-only|s/\n\t\tmethod GET HEAD\n(\t\tpath \/app\/timeline\n)/\n\t\tmethod GET\n$1/|FAIL PROBE HEAD /app/timeline got 299 (java), expected 298 (next)
timeline-wide|s/(\n\t\tmethod GET HEAD\n\t\tpath \/app\/timeline)\n/$1*\n/|FAIL /app/timeline/ got 298 (next), expected 299 (java)
timeline-missing|s/\t\@medioo_papers \{\n\t\tmethod GET HEAD\n\t\tpath \/app\/timeline\n\t\}\n\thandle \@medioo_papers \{\n\t\treverse_proxy medioo-web:3000\n\t\}\n//|FAIL /app/timeline got 299 (java), expected 298 (next)|FAIL /app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100001 got 299 (java), expected 298 (next)|FAIL PROBE HEAD /app/timeline got 299 (java), expected 298 (next)
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
