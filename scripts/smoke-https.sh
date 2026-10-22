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
# Next's 404 for a missing chunk is text/plain and Java's is JSON, and both carry the four headers, so that row reads
# the Content-Type: on status and headers alone it would pass on Java's answer, the one thing it exists to tell apart.
#
# Header classes. Caddy's medioo header block owns HSTS, X-Content-Type-Options and Referrer-Policy on every path
# (it runs at response-write time and overwrites the upstream); each upstream owns its CSP and X-Frame-Options.
#   next    /app/healthz, /app/preview   the CSP carries a per-request nonce (medioo-web's middleware)
#   static  /_next/static/*, /fonts/*, /brand/*   medioo-web's assets: the four headers below, once each, and no CSP
#           required (the hashed chunks skip the middleware, so they carry none; the fonts and brand files carry one)
#   page    /app/, /             Java's HTML pages
#   api     /actuator/health, /app/api/me, the webhook
# Referrer-Policy stays strict-origin-when-cross-origin, the value production sends today (PO note in the W1c PR).
# Each of X-Frame-Options, X-Content-Type-Options, Referrer-Policy and HSTS must arrive exactly once: a browser acts on
# every copy it receives, not on the last one (two conflicting X-Frame-Options are ignored, so framing is allowed),
# so an upstream or a Caddy edit that appends instead of replacing fails the row whatever the last value says.
#
# Routing stops at the F0 paths for now (plan Wave 3, Task F): /app/healthz, the preview path (/app/preview and
# /app/preview/*) and the assets (/_next/static/*, /fonts/*, /brand/*). Every page is still Java's, and Java still owns
# the API: /app/api/me must be Java's JSON 401, so a route that sent /app/api/* to Next fails here. Each flip PR moves
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
#   check is one of: -                      nothing beyond status and headers
#                    json:<field>=<value>   the top-level JSON field equals <value>
#                    html:<marker>          the body contains <marker> (an attribute, not a word)
#                    location:<value>       the Location header equals <value> exactly
#                    type:<value>           the Content-Type header, parameters aside, equals <value>
#   a path starting {old} is asked of the old hostname (SMOKE_OLD_BASE); every other one of <base-url>
TABLE='/app/healthz|200|next|json:service=medioo-web
/actuator/health|200|api|json:status=UP
/app/api/me|401|api|json:reason=UNAUTHENTICATED
/app/|200|page|html:name="whatsapp_number"
/|200|page|-
/medscribeai|200|-|-
/medscribeai/|200|-|-
/baymax|301|-|location:/app
/baymax/|301|-|location:/app/
/api/v1/baymax/wa/webhook|403|api|-
/_next/static/x.js|404|static|type:text/plain
/fonts/noto-sans-latin.woff2|200|static|type:font/woff2
/brand/mio-head.webp|200|static|type:image/webp
/app/preview|307|next|location:/app/?next=%2Fapp%2Fpreview
/app/preview/|307|next|location:/app/?next=%2Fapp%2Fpreview%2F
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

# check_row <path> <status> <class> <check>: prints one PASS or FAIL line; returns 1 on FAIL
check_row() {
  local path="$1" want="$2" class="$3" check="$4" body="$TMP/body" hdr="$TMP/headers" code reasons=() v url left times
  : > "$body"; : > "$hdr"
  if [ "${path#"{old}"}" != "$path" ]; then url="$OLD_BASE${path#"{old}"}"; path="$url"; else url="$BASE$path"; fi
  left=$((END - SECONDS))
  if [ "$left" -le 0 ]; then
    echo "FAIL $path deadline of ${DEADLINE}s reached; not asked"
    return 1
  fi
  code=$(curl -s -o "$body" -D "$hdr" -w '%{http_code}' --connect-timeout $((left < 5 ? left : 5)) \
           --max-time $((left < 15 ? left : 15)) "$url" 2>/dev/null) || true
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
    check_row "$path" "$status" "$class" "$check" >> "$TMP/table" || failed=$((failed + 1))
  done <<< "$TABLE"
  echo "$failed"
}

ROWS=$(printf '%s\n' "$TABLE" | grep -c '|')
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
echo "smoke-https: $ROWS rows, $failed failed, attempt $attempt of $TRIES, deadline ${DEADLINE}s ($BASE)"
[ "$failed" = 0 ]
