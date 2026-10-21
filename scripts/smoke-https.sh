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
#
# Header classes. Caddy's medioo header block owns HSTS, X-Content-Type-Options and Referrer-Policy on every path
# (it runs at response-write time and overwrites the upstream); each upstream owns its CSP and X-Frame-Options.
#   next  /app/healthz       the CSP carries a per-request nonce (medioo-web's middleware)
#   page  /app/, /           Java's HTML pages
#   api   /actuator/health, the webhook
# Referrer-Policy stays strict-origin-when-cross-origin, the value production sends today (PO note in the W1c PR).
#
# A JSON field is read with jq, never grepped: the health body has "status":"UP" in every component, so a first-match
# grep would pass a top-level DOWN. Bodies are never printed: the CI log is public (DR-38) and the health body
# carries disk, path and database details. Only paths, status codes and header values reach the output.
#
# SMOKE_TRIES (default 10) and SMOKE_SLEEP (default 5 s): the whole table is retried until it passes, so a Caddy
# that has not yet reached a just-started upstream is waited for; only the last try's table is printed in full.
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
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

REFERRER="strict-origin-when-cross-origin"

# The table: path | status | header class | check
#   check is one of: -                      nothing beyond status and headers
#                    json:<field>=<value>   the top-level JSON field equals <value>
#                    html:<marker>          the body contains <marker> (an attribute, not a word)
#                    location:<value>       the Location header equals <value> exactly
TABLE='/app/healthz|200|next|json:service=medioo-web
/actuator/health|200|api|json:status=UP
/app/|200|page|html:name="whatsapp_number"
/|200|page|-
/medscribeai|200|-|-
/medscribeai/|200|-|-
/baymax|301|-|location:/app
/baymax/|301|-|location:/app/
/api/v1/baymax/wa/webhook|403|api|-'

# header <file> <lower-case name>: the last value of that header, CR stripped (empty when absent)
header() {
  tr -d '\r' < "$1" | awk -v n="$2" '
    index($0, ":") { k = tolower(substr($0, 1, index($0, ":") - 1)); if (k == n) { v = substr($0, index($0, ":") + 1); sub(/^[ \t]+/, "", v); last = v } }
    END { print last }'
}

# check_row <path> <status> <class> <check>: prints one PASS or FAIL line; returns 1 on FAIL
check_row() {
  local path="$1" want="$2" class="$3" check="$4" body="$TMP/body" hdr="$TMP/headers" code reasons=() v
  : > "$body"; : > "$hdr"
  code=$(curl -s -o "$body" -D "$hdr" -w '%{http_code}' --connect-timeout 5 --max-time 15 "$BASE$path" 2>/dev/null) || true
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
  esac

  if [ "$class" != - ]; then
    local csp
    csp=$(header "$hdr" content-security-policy)
    if [ -z "$csp" ]; then
      reasons+=("content-security-policy is missing")
    elif [ "$class" = next ] && [[ "$csp" != *"'nonce-"* ]]; then
      reasons+=("content-security-policy has no nonce (not medioo-web's policy)")
    fi
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
  echo "attempt $attempt of $TRIES: $failed of $ROWS rows failed; retrying in ${SLEEP}s"
  sleep "$SLEEP"
done

sed 's/;/; /g' "$TMP/table"
echo "smoke-https: $ROWS rows, $failed failed, attempt $attempt of $TRIES ($BASE)"
[ "$failed" = 0 ]
