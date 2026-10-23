#!/usr/bin/env bash
#
# The https upstream table (DR-40 A1, plan W1c): one row per path class, through Caddy, exactly as a browser sees it.
#
#   scripts/smoke-https.sh https://medioo.eliooo.org
#
# Exits 0 only when every row passes. Run by CI after every deploy, and by hand after every Caddy edit and every
# rollback (docs/RUNBOOK.md -> Routing a path to medioo-web). Tested by scripts/test_smoke_https.sh.
#
# The expected values are what production answered on 2026-10-21, measured before the /app/healthz flip (plan W1c
# Step 1), not what the plan guessed: /baymax answers 301 with `Location: /app` (Java's redirect, not `/`), the
# webhook GET with no parameters answers 403 (Java's verification refusal), /medscribeai and /medscribeai/ answer
# 200. The one row that failed then is /app/healthz (Java's 404, before Caddy routed it to medioo-web).
# The F0 rows (plan Wave 3, Task F) were measured on 2026-10-22: /app/api/me against production (Java's 401
# UNAUTHENTICATED), and the Next rows through the W1d Caddy replica against the deployed sha's medioo-web image (stub
# Java signed out). Signed out, the preview path is not a page: the preview gate reads `me`, gets the 401 and sends the
# visitor to the sign-in page holding the preview (307 to /app/?next=%2Fapp%2Fpreview), not the 200 the plan guessed.
# KNOWN GAP, not the expected behaviour: the plan expects `/app/preview|200|next|html:name="whatsapp_number"`. With
# the gate as merged in W3, no viewer in any state reaches a Next screen through the preview (signed out: this 307 to
# the sign-in page, Java's until F3 and Next's from it; signed in and allowlisted: the sign-in pages send them on to
# /app/home, which from F5 is Next's chat page outside the preview; signed in and not allowlisted: Next's 404). The
# two 307 rows pin what the code does today so a change is seen; they return to the plan's 200 row once the gate lets the two sign-in reads through for a signed-out visitor (a PO ruling, W3
# follow-up), replayed red against the image of that day.
# A POST to the preview (measured 2026-10-22 through the same replica, signed out) answers as the GET does: 307 to
# /app/?next=..., medioo-web's nonce CSP, for /app/preview and /app/preview/verify alike. The preview block does not
# restrict the method (plan F0 row); this table asks GET only.
# Just outside each F0 matcher the answer is still Java's JSON 404 (production, 2026-10-22, read-only GET):
# /app/previewx (outside the preview matcher; `/app/preview*` would take it), /_next/image (outside /_next/static/*;
# `/_next/*` would take it) and /app/preview/probe (excluded from the preview matcher: the DR-42 budget probe page
# reads nothing from Java, so the preview gate never runs on it, and routed it rendered 200 to any visitor through the
# replica). These rows are the routing's other direction: a matcher that reaches too far fails one of them.
# Next's 404 for a missing chunk is text/plain and Java's is JSON, and both carry the four headers, so that row reads
# the Content-Type: on status and headers alone it would pass on Java's answer, the one thing it exists to tell apart.
# The F7 rows (plan Wave 3, Task F; DR-49, PO ruling 2026-10-23) were measured on 2026-10-23. Before the flip,
# production answered GET /app/add signed out with Java's session filter: 303 to /app/ (no held place), Java's CSP;
# HEAD with Java's JSON 404. After it, medioo-web's add sheet (plan W7) answers GET and HEAD signed out with a 307 to
# the sign-in page holding the place (/app/?next=%2Fapp%2Fadd), its nonce CSP: measured against the deployed sha's
# build (0015f5b) with stub Java signed out, not through the W1d replica (Docker was down; Caddy changes neither the
# status nor the Location). HEAD is a PROBE row because this run sent GET only when F7 was written (F3 added the
# method column): the route probe sends it as HEAD, so a matcher of `method GET` alone fails there. POST /app/add is Java's upload (the old sheet's form and the paper
# page's retake form post there): the Caddy matcher is GET and HEAD only, and its row is a PROBE row, never sent here.
# UNMEASURED, from the code: its 303 to /app/ is what Java's session filter (BaymaxWebUiRouter.requireSession) answers
# a request with no session; it was not posted to production, and the W1d replica measurement (POST /app/add signed
# out, image 0015f5b, scripts/budget-behind-caddy.sh) is pending because Docker was down. The route probe reads only
# the row's class (Java), so no check depends on the 303 or the Location until it is measured.
# /app/add/ (trailing slash, outside the exact `path /app/add`) is still Java's JSON 404 (production, 2026-10-23,
# read-only GET: 404, application/json, statusCode 400): a matcher widened to `/app/add*` fails that row.
# The F3 rows (plan Wave 3, Task F: GET and HEAD /app, /app/ and /app/verify to medioo-web) were measured on 2026-10-23:
# before the flip against production (GET and HEAD only), and the Next answers through a local replica: the deployed
# sha's medioo-web tree as a standalone build bound as the image binds it (HOSTNAME=0.0.0.0, medioo-web/Dockerfile),
# behind this repo's medioo block in Caddy v2.11.4 (the box's version), with the e2e stub Java signed out (Docker was
# down that day, so not the image itself; the box run at the flip is the image's). A first run bound to 127.0.0.1
# rendered /app/preview 200 without reading `me`: on a loopback literal Next proxies its own rewrite to itself and the
# middleware ran twice (now refused, src/middleware.ts). The sign-in rows involve no rewrite and answered the same
# under both binds. Before the flip Java answered GET /app
# and /app/ with its number page (200, its CSP, no nonce), GET /app/verify with 303 to /app/, and HEAD on all three with
# its JSON 404 (a WebFlux GET route does not take HEAD); after it, Next answers the number page under its nonce CSP,
# and /app/verify with no pending login is its 307 to /app/, GET and HEAD alike. The sign-in POSTs stay Java's (S3:
# the matcher is GET/HEAD only): with no login cookie POST /app/verify is 303 to /app/, and an empty POST /app/login is
# the number page again with its error; neither reads or writes anything (SignInPages: no cookie, no number, no call),
# which is why they are production rows and not PROBE rows. S1's no-JS fallback stays Java's: /app/basic is its
# number page posting to /app/basic/login, and the language switch on its code step lands back on it. Just outside the
# sign-in matcher GET /app/login (S3: never routed, it has no GET) and /app/verifyx are Java's JSON 404.
# Who compresses (PO ruling P3, 2026-10-23): medioo-web sends every body unencoded (next.config.ts compress: false) and
# Caddy's `encode zstd gzip` compresses it. The /brand/medioo-light.svg row asks with `Accept-Encoding: zstd, gzip` and
# needs zstd, which only Caddy produces: Next's own compression speaks gzip, so an image that compresses itself again
# fails the row with "content-encoding is [gzip]" (production answered exactly that on 2026-10-23, before P3 deployed),
# and a Caddy that stopped encoding fails it with "[]". After a rollback the table is run from a checkout of the sha
# deployed (RUNBOOK -> Rollback), and a sha from before P3 has no such row: its image compresses itself again.
# The F5 rows (plan Wave 3, Task F; DR-49, PO ruling 2026-10-23) were measured on 2026-10-23. Before the flip,
# production answered GET /app/home signed out with Java's session filter: 303 to /app/ (no held place), Java's CSP;
# HEAD with Java's JSON 404 (Java routes GET alone). After it, medioo-web's chat page (plan W5) answers GET and HEAD
# signed out with a 307 to the sign-in page holding the place (/app/?next=%2Fapp%2Fhome, the query kept), its nonce
# CSP: measured against the deployed sha's build (a529160) with stub Java signed out, and again on 2026-10-23 through
# the W1d replica's own Caddyfile (BUDGET_REPLICA_ONLY=1) on Caddy v2.11.7 in front of `next start` of the F5 head
# (Docker was down, so the replica ran without containers): GET and HEAD alike, 307, the same Location, the nonce
# CSP and the four headers once each. HEAD is a PROBE row, as F7's HEAD /app/add is: the route probe sends it as HEAD,
# so a matcher of `method GET` alone fails there (making both HEAD rows production rows now that F3 made this run
# method-aware is the follow-up posted on #155). Java has no POST /app/home (docs/infra/app-routes.txt) and the
# matcher is GET and HEAD only, so a POST stays Java's; its row is a PROBE row, never sent by this run. The W1d replica
# cannot measure it (it points Java's upstream at medioo-web too, so a POST through it is Next's 307), so it was
# measured against Java itself on 2026-10-23, before the flip (production, signed out, no body; Java has no POST route
# there, so nothing is read or written): POST /app/home answers 404, application/json, statusCode 400, the same
# answer as HEAD /app/home. The route probe reads the row's class (Java): a matcher that let a POST through to Next
# fails there (`home-any-method`).
# /app/home/ (trailing slash, outside the exact `path /app/home`) is still Java's JSON 404 (production, 2026-10-23,
# read-only GET: 404, application/json, statusCode 400); Next would answer it with a 307 holding /app/home/, so a
# matcher widened to `/app/home*` fails that row.
# The F10 rows (plan Wave 3, Task F; DR-49, PO ruling 2026-10-23) were measured on 2026-10-23. Before the flip,
# production answered GET /app/family and GET /app/patients/<id>/profile signed out with Java's session filter: 303 to
# /app/ (no held place), Java's CSP; HEAD on both with Java's JSON 404 (Java routes GET alone). After it, medioo-web's
# family tab and a patient's profile page (plan W10) answer GET and HEAD signed out with a 307 to the sign-in page
# holding the place (/app/?next=%2Fapp%2Ffamily, /app/?next=%2Fapp%2Fpatients%2F<id>%2Fprofile), their nonce CSP:
# measured against the deployed sha's build (be3a2ff) with stub Java signed out, and through the W1d replica's own
# Caddyfile (BUDGET_REPLICA_ONLY=1) on a local Caddy v2.11.7 in front of that `next start` (Docker was down, so no
# containers): GET and HEAD alike, 307, the same Location, the nonce CSP and the four headers once each. The patient id
# is the fixtures' fictional first patient; signed out, no row reads a patient. The HEAD rows are PROBE rows, as F7's
# and F5's are, until the #155 follow-up. Java routes no POST /app/family (docs/infra/app-routes.txt), so a POST there
# is the JSON 404 its HEAD measured (not POSTed to production; the same no-route answer F5 measured for POST
# /app/home). POST /app/patients/<id>/profile is Java's profile save and stays Java's: the matcher is GET and HEAD
# only, and its row is a PROBE row, never sent here. UNMEASURED, from the code: its 303 to /app/ is Java's session
# filter (BaymaxWebUiRouter.requireSession, which ProfilePages' GET and POST share), the answer its GET measured; the
# route probe reads only the row's class (Java). The boundary rows, each Java's on production 2026-10-23 (read-only
# GET): /app/family/ and /app/patients/<id>/profile/ answer Java's JSON 404, where Next answers a 307 holding the
# slash (a matcher widened to `/app/family*`, or a regexp that lost its `$`, fails there); /app/patients/x/profile
# (an id that is not a uuid, outside `[0-9a-f-]{36}`) and /app/patients/<id> (the patient page, F8's) answer Java's
# 303 to /app/, where Next answers a 307 and its HTML 404 (a matcher of `/app/patients/*/profile` or
# `/app/patients/*` fails there).
#
# Header classes. Caddy's medioo header block owns HSTS, X-Content-Type-Options and Referrer-Policy on every path
# (it runs at response-write time and overwrites the upstream); each upstream owns its CSP and X-Frame-Options.
#   next    /app/healthz, /app/preview, /app/add, /app, /app/, /app/verify, /app/home, /app/family,
#           /app/patients/<id>/profile   the CSP carries a per-request nonce
#           (medioo-web's middleware)
#   static  /_next/static/*, /fonts/*, /brand/*   medioo-web's assets: the four headers below, once each, and no CSP
#           required (the hashed chunks skip the middleware, so they carry none; the fonts and brand files carry one)
#   page    /, /app/basic, POST /app/login, /app/patients/x/profile, /app/patients/<id>   Java's HTML pages (the
#           last two its session filter's 303)
#   api     /actuator/health, /app/api/me, the webhook
# Referrer-Policy stays strict-origin-when-cross-origin, the value production sends today (PO note in the W1c PR).
# Each of X-Frame-Options, X-Content-Type-Options, Referrer-Policy and HSTS must arrive exactly once: a browser acts on
# every copy it receives, not on the last one (two conflicting X-Frame-Options are ignored, so framing is allowed),
# so an upstream or a Caddy edit that appends instead of replacing fails the row whatever the last value says.
#
# Routing stops at the F0 paths, F7's page, F3's, F5's and F10's for now (plan Wave 3, Task F): /app/healthz, the
# preview path (/app/preview and /app/preview/*, less /app/preview/probe), the assets (/_next/static/*, /fonts/*,
# /brand/*) and, for GET and HEAD only, F7's /app/add (POST /app/add stays Java's), F3's sign-in pages /app, /app/ and
# /app/verify, F5's /app/home (the chat page), and F10's /app/family and /app/patients/<uuid>/profile (the family tab
# and a patient's profile; POST /app/patients/<id>/profile, the profile save, stays Java's). Every other page is still
# Java's, and Java still owns the API: /app/api/me must be Java's JSON 401, so a route that sent /app/api/* to Next
# fails here, as does a matcher wider than its row (the boundary rows above: three for F0, /app/add/ for F7,
# /app/login and /app/verifyx for F3, /app/home/ for F5, four for F10) or one that forgot the method (the two sign-in
# POST rows). Each flip PR moves
# its own page's rows. scripts/caddy-route-probe.sh sends this same table through a local Caddy running the repo's
# Caddyfile.medioo and checks that each row reaches the upstream its class names: the routing is derived from this
# table, not from a second list.
# The old hostname (DR-15): {old}<path> is SMOKE_OLD_BASE (default https://baymax.eliooo.org) plus the path, and must
# answer 301 to the same path on medioo.eliooo.org, so every link already sent to a family keeps working.
#
# A JSON field is read with jq, never grepped: the health body has "status":"UP" in every component, so a first-match
# grep would pass a top-level DOWN. Bodies are never printed: the CI log is public (DR-38) and the health body
# carries disk, path and database details. Only paths, status codes and header values reach the output.
#
# SMOKE_TRIES (default 10) and SMOKE_SLEEP (default 5 s): the whole table is retried until it passes, so a Caddy
# that has not yet reached a just-started upstream is waited for; only the last try's table is printed in full.
# SMOKE_DEADLINE (default 90 s) bounds the whole run, every try and row included: each request's --max-time shrinks
# to what is left, and no request or try starts after it. Without it a Caddy that accepts and never answers would
# hold the deploy job for tries x rows x 15 s (over 25 minutes) instead of failing.
set -uo pipefail

BASE="${1:-}"
if [ -z "$BASE" ]; then
  echo "usage: smoke-https.sh <base-url>   e.g. https://medioo.eliooo.org" >&2
  exit 2
fi
BASE="${BASE%/}"
command -v jq >/dev/null 2>&1 || { echo "smoke-https: jq is required (the JSON rows read a field, never grep)" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "smoke-https: curl is required" >&2; exit 2; }

TRIES="${SMOKE_TRIES:-10}"
SLEEP="${SMOKE_SLEEP:-5}"
DEADLINE="${SMOKE_DEADLINE:-90}"
OLD_BASE="${SMOKE_OLD_BASE:-https://baymax.eliooo.org}"
OLD_BASE="${OLD_BASE%/}"
for v in TRIES SLEEP DEADLINE; do
  [[ "${!v}" =~ ^[0-9]+$ ]] || { echo "smoke-https: SMOKE_$v '${!v}' is not a whole number" >&2; exit 2; }
done
END=$((SECONDS + DEADLINE))
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

REFERRER="strict-origin-when-cross-origin"

# The table: path | status | header class | check
#   path is "/path" (GET), "METHOD /path" (sent with that method: HEAD as curl --head, any other as curl -X), or
#        "PROBE METHOD /path": a PROBE row is sent only by scripts/caddy-route-probe.sh, through a local Caddy with fixed
#        upstreams, and this script skips it, so a row that would change data is never sent to production. Every
#        row whose method is not GET or HEAD is PROBE except the harmless sign-in POSTs (exactly POST /app/login and
#        POST /app/verify); any other non-PROBE row is refused before anything is sent (exit 2, allowed_row below).
#   check is one of: -                      nothing beyond status and headers
#                    json:<field>=<value>   the top-level JSON field equals <value>
#                    html:<marker>          the body contains <marker> (an attribute, not a word)
#                    location:<value>       the Location header equals <value> exactly
#                    type:<value>           the Content-Type header, parameters aside, equals <value>
#                    encoding:<value>       asked with `Accept-Encoding: zstd, gzip`, the Content-Encoding equals <value>
#   a path starting {old} is asked of the old hostname (SMOKE_OLD_BASE); every other one of <base-url>
#   a path starting `PROBE ` (`PROBE <METHOD> /path`) is sent only by caddy-route-probe.sh, never by this run: it is
#   a request this run leaves to the probe (F7's, F5's and F10's HEAD rows, until the #155 follow-up makes them
#   production rows), or one that must not reach production (POST /app/add is the upload, POST
#   /app/patients/<id>/profile the profile save); the probe reads its class alone
TABLE='/app/healthz|200|next|json:service=medioo-web
/actuator/health|200|api|json:status=UP
/app/api/me|401|api|json:reason=UNAUTHENTICATED
/app/|200|next|html:name="whatsapp_number"
/app|200|next|html:name="whatsapp_number"
HEAD /app/|200|next|-
/app/verify|307|next|location:/app/
HEAD /app/verify|307|next|location:/app/
/app/login|404|-|json:statusCode=400
/app/verifyx|404|-|json:statusCode=400
/app/basic|200|page|html:action="/app/basic/login"
/lang/en?back=%2Fapp%2Fbasic%2Fverify|303|-|location:/app/basic/verify
POST /app/verify|303|-|location:/app/
POST /app/login|200|page|html:name="whatsapp_number"
/|200|page|-
/medscribeai|200|-|-
/medscribeai/|200|-|-
/baymax|301|-|location:/app
/baymax/|301|-|location:/app/
/api/v1/baymax/wa/webhook|403|api|-
/_next/static/x.js|404|static|type:text/plain
/fonts/noto-sans-latin.woff2|200|static|type:font/woff2
/brand/mio-head.webp|200|static|type:image/webp
/brand/medioo-light.svg|200|static|encoding:zstd
/app/preview|307|next|location:/app/?next=%2Fapp%2Fpreview
/app/preview/|307|next|location:/app/?next=%2Fapp%2Fpreview%2F
/app/previewx|404|-|json:statusCode=400
/_next/image|404|-|json:statusCode=400
/app/preview/probe|404|-|json:statusCode=400
/app/add|307|next|location:/app/?next=%2Fapp%2Fadd
/app/add/|404|-|json:statusCode=400
PROBE HEAD /app/add|307|next|location:/app/?next=%2Fapp%2Fadd
PROBE POST /app/add|303|page|location:/app/
/app/home|307|next|location:/app/?next=%2Fapp%2Fhome
/app/home/|404|-|json:statusCode=400
PROBE HEAD /app/home|307|next|location:/app/?next=%2Fapp%2Fhome
PROBE POST /app/home|404|-|json:statusCode=400
/app/family|307|next|location:/app/?next=%2Fapp%2Ffamily
/app/family/|404|-|json:statusCode=400
PROBE HEAD /app/family|307|next|location:/app/?next=%2Fapp%2Ffamily
PROBE POST /app/family|404|-|json:statusCode=400
/app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile|307|next|location:/app/?next=%2Fapp%2Fpatients%2F5e9edf6d-0a2a-4913-8675-9d07a4100001%2Fprofile
/app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile/|404|-|json:statusCode=400
/app/patients/x/profile|303|page|location:/app/
/app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001|303|page|location:/app/
PROBE HEAD /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile|307|next|location:/app/?next=%2Fapp%2Fpatients%2F5e9edf6d-0a2a-4913-8675-9d07a4100001%2Fprofile
PROBE POST /app/patients/5e9edf6d-0a2a-4913-8675-9d07a4100001/profile|303|page|location:/app/
{old}/app/home|301|-|location:https://medioo.eliooo.org/app/home'

# header <file> <lower-case name>: the last value of that header, CR stripped (empty when absent)
header() {
  tr -d '\r' < "$1" | awk -v n="$2" '
    index($0, ":") { k = tolower(substr($0, 1, index($0, ":") - 1)); if (k == n) { v = substr($0, index($0, ":") + 1); sub(/^[ \t]+/, "", v); last = v } }
    END { print last }'
}

# header_count <file> <lower-case name>: how many times that header was sent
header_count() {
  tr -d '\r' < "$1" | awk -v n="$2" 'index($0, ":") && tolower(substr($0, 1, index($0, ":") - 1)) == n { c++ } END { print c + 0 }'
}

# check_row <path> <status> <class> <check>: prints one PASS or FAIL line; returns 1 on FAIL. <path> may carry a
# method ("POST /app/verify"); the line names the row as written.
check_row() {
  local path="$1" want="$2" class="$3" check="$4" body="$TMP/body" hdr="$TMP/headers" code reasons=() v url left times
  local method=GET how=()
  : > "$body"; : > "$hdr"
  allowed_row "$path" || { echo "FAIL $path refused: a production row changes no data (PROBE only)"; return 1; }
  if [[ "$path" =~ ^([A-Z]+)\ (/.*)$ ]]; then method="${BASH_REMATCH[1]}"; path="${BASH_REMATCH[2]}"; fi
  case "$method" in GET) ;; HEAD) how=(--head) ;; *) how=(-X "$method") ;; esac
  if [ "${path#"{old}"}" != "$path" ]; then url="$OLD_BASE${path#"{old}"}"; path="$url"; else url="$BASE$path"; fi
  [ "$method" = GET ] || path="$method $path"
  left=$((END - SECONDS))
  if [ "$left" -le 0 ]; then
    echo "FAIL $path deadline of ${DEADLINE}s reached; not asked"
    return 1
  fi
  # Only the encoding row offers an encoding; every other row reads its body as sent plain.
  local offer=()
  case "$check" in encoding:*) offer=(-H "Accept-Encoding: zstd, gzip") ;; esac
  code=$(curl -s -o "$body" -D "$hdr" -w '%{http_code}' --connect-timeout $((left < 5 ? left : 5)) \
           --max-time $((left < 15 ? left : 15)) ${how[@]+"${how[@]}"} ${offer[@]+"${offer[@]}"} "$url" 2>/dev/null) || true
  code="${code:-000}"
  [ "$code" = "$want" ] || reasons+=("HTTP $code, expected $want")

  case "$check" in
    json:*)
      local kv="${check#json:}" field value
      field="${kv%%=*}"; value="${kv#*=}"
      v=$(jq -r --arg f "$field" 'if type == "object" and has($f) then (.[$f] | tostring) else "" end' "$body" 2>/dev/null | head -1)
      [ "$v" = "$value" ] || reasons+=("$field is [$v], expected [$value]") ;;
    html:*)
      grep -qF -- "${check#html:}" "$body" || reasons+=("no ${check#html:} in the page") ;;
    location:*)
      v=$(header "$hdr" location)
      [ "$v" = "${check#location:}" ] || reasons+=("location is [$v], expected [${check#location:}]") ;;
    type:*)
      v=$(header "$hdr" content-type); v="${v%%;*}"; v="${v%"${v##*[![:space:]]}"}"
      [ "$v" = "${check#type:}" ] || reasons+=("content-type is [$v], expected [${check#type:}]") ;;
    encoding:*)
      v=$(header "$hdr" content-encoding)
      [ "$v" = "${check#encoding:}" ] || reasons+=("content-encoding is [$v], expected [${check#encoding:}]") ;;
  esac

  if [ "$class" != - ]; then
    local csp
    csp=$(header "$hdr" content-security-policy)
    if [ "$class" = static ]; then
      :
    elif [ -z "$csp" ]; then
      reasons+=("content-security-policy is missing")
    elif [ "$class" = next ] && [[ "$csp" != *"'nonce-"* ]]; then
      reasons+=("content-security-policy has no nonce (not medioo-web's policy)")
    fi
    for v in x-frame-options x-content-type-options referrer-policy strict-transport-security; do
      times=$(header_count "$hdr" "$v")
      [ "$times" -le 1 ] || reasons+=("$v is sent $times times, expected once")
    done
    v=$(header "$hdr" x-frame-options);        [ "$v" = DENY ] || reasons+=("x-frame-options is [$v], expected [DENY]")
    v=$(header "$hdr" x-content-type-options); [ "$v" = nosniff ] || reasons+=("x-content-type-options is [$v], expected [nosniff]")
    v=$(header "$hdr" referrer-policy);        [ "$v" = "$REFERRER" ] || reasons+=("referrer-policy is [$v], expected [$REFERRER]")
    [ -n "$(header "$hdr" strict-transport-security)" ] || reasons+=("strict-transport-security is missing")
  fi

  if [ "${#reasons[@]}" -eq 0 ]; then
    if [ "$class" = - ]; then echo "PASS $path HTTP $code"; else echo "PASS $path HTTP $code ($class)"; fi
    return 0
  fi
  local IFS=';'
  echo "FAIL $path ${reasons[*]}"
  return 1
}

# run_table: the whole table into $TMP/table; prints the number of failed rows
run_table() {
  local failed=0 path status class check
  : > "$TMP/table"
  while IFS='|' read -r path status class check; do
    [ -n "$path" ] || continue
    [ "${path#PROBE }" = "$path" ] || continue   # the route probe's alone; never sent to production
    check_row "$path" "$status" "$class" "$check" >> "$TMP/table" || failed=$((failed + 1))
  done <<< "$TABLE"
  echo "$failed"
}

# A production row never changes data. It is sent after every deploy and on every retry, to the one real family's
# production, so the rule is an allowlist, not a list of paths that look dangerous: outside PROBE, a row is a bare path
# (GET), `GET /path` or `HEAD /path`, or exactly `POST /app/login` or `POST /app/verify` (SignInPages: with no cookie
# and no number they read and write nothing). Every other row, whatever its method or path (a PUT or DELETE on
# /app/api, POST /app/basic/login, which sends a WhatsApp code, an opt-out), is refused before any request (exit 2).
# check_row asks allowed_row again before its curl, so a row cannot reach the network by another way in.
allowed_row() { # allowed_row <path column>
  case "$1" in
    "POST /app/login" | "POST /app/verify") return 0 ;;
  esac
  [[ "$1" =~ ^((GET|HEAD)\ )?(/|\{old\}/)[^\ ]*$ ]]
}
refused=$(printf '%s\n' "$TABLE" | cut -d'|' -f1 | grep -vE '^PROBE ' | while IFS= read -r row; do
  [ -n "$row" ] && ! allowed_row "$row" && printf '%s\n' "$row"
done)
if [ -n "$refused" ]; then
  echo "smoke-https: refusing to send [$(printf '%s' "$refused" | tr '\n' ';')] to $BASE: a production row is GET or HEAD, or exactly POST /app/login or POST /app/verify; any other must be a PROBE row" >&2
  exit 2
fi
ROWS=$(printf '%s\n' "$TABLE" | grep '|' | grep -cvE '^PROBE ')
PROBES=$(printf '%s\n' "$TABLE" | grep '|' | grep -cE '^PROBE ')
attempt=0
while :; do
  attempt=$((attempt + 1))
  failed=$(run_table)
  if [ "$failed" = 0 ] || [ "$attempt" -ge "$TRIES" ]; then break; fi
  left=$((END - SECONDS))
  if [ "$left" -le 0 ]; then echo "attempt $attempt of $TRIES: $failed of $ROWS rows failed; deadline of ${DEADLINE}s reached"; break; fi
  pause=$((left < SLEEP ? left : SLEEP))
  echo "attempt $attempt of $TRIES: $failed of $ROWS rows failed; retrying in ${pause}s"
  sleep "$pause"
  [ $((END - SECONDS)) -gt 0 ] || { echo "deadline of ${DEADLINE}s reached; not retrying"; break; }
done

sed 's/;/; /g' "$TMP/table"
echo "smoke-https: $ROWS rows, $failed failed, $PROBES PROBE rows left to caddy-route-probe.sh, attempt $attempt of $TRIES, deadline ${DEADLINE}s ($BASE)"
[ "$failed" = 0 ]
