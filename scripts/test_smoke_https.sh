#!/usr/bin/env bash
#
# Tests for scripts/smoke-https.sh (plan W1c), against a local stub server (node, written below) that answers the
# path-class table the way production answered it on 2026-10-21, after the /app/healthz flip. Real curl, real HTTP,
# no network beyond 127.0.0.1.
#
#   bash scripts/test_smoke_https.sh
#   SMOKE_SH=/path/to/copy.sh bash scripts/test_smoke_https.sh   # replays the same cases against another copy
#
# What is under test, in both directions:
# - the flipped table passes, every row, and the row count is the table's (a smoke that checks nothing cannot pass);
# - today's table (Java answering /app/healthz with its 404) fails on /app/healthz and nothing else;
# - each row, broken alone, fails that row and only that row: status, redirect target, the JSON field (parsed, not
#   grepped: a nested "status":"UP" or "service":"medioo-web" before the top-level one must not pass), the HTML
#   marker, and each security header on each path class (the nonce CSP on the Next path); every row has a break;
# - a security header sent twice fails, whatever the last value says (a browser sees both: conflicting
#   X-Frame-Options are ignored, so SAMEORIGIN then DENY allows framing);
# - routing stopped at /app/healthz: /_next/* is still Java's JSON 404, not Next's (moves to the next class with the
#   PR that routes /_next/*);
# - the old hostname still redirects every path to the new one (DR-15: links already sent to families);
# - a body is never printed (the CI log is public, DR-38; the health body carries disk and database details);
# - a transient failure is retried, an unreachable host fails rather than hangs, and a host that accepts and never
#   answers fails within SMOKE_DEADLINE, not after tries x rows x --max-time.
set -uo pipefail

SMOKE="${SMOKE_SH:-$(cd "$(dirname "$0")" && pwd)/smoke-https.sh}"
WORK=$(mktemp -d)
STUB_PID=""
cleanup() { [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT
FAILED=0
CASES=0
# the table's size: /app/healthz /actuator/health /app/ / /medscribeai /medscribeai/ /baymax /baymax/ webhook
# /_next/static/x.js and the old host's /app/home
ROWS=11

fail() { echo "FAIL: $1"; FAILED=1; }

cat > "$WORK/stub.cjs" <<'STUB'
// The production table, served on 127.0.0.1. STUB_BREAK names one thing to break; STUB_FLAKY=<n> makes the first
// <n> requests to /app/healthz answer 502 (Caddy before the upstream is up); STUB_HANG=1 accepts every request and
// never answers (a Caddy that stalls). /old-host/* stands in for https://baymax.eliooo.org/* (SMOKE_OLD_BASE).
const http = require("http");
const fs = require("fs");
const brk = process.env.STUB_BREAK || "";
const hang = process.env.STUB_HANG === "1";
let flaky = Number(process.env.STUB_FLAKY || 0);
const JAVA_CSP = "default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' https: http: blob:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
const NEXT_CSP = "default-src 'none'; script-src 'self' 'nonce-c3R1Yk5vbmNlMTIzNDU2Nw=='; style-src 'self'; img-src 'self' https: http: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'";

function headers(path, csp) {
  const h = {
    "Content-Security-Policy": csp,
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Strict-Transport-Security": "max-age=31536000",
  };
  const drop = (name) => { delete h[name]; };
  if (brk === "api-no-xfo" && path === "/actuator/health") drop("X-Frame-Options");
  if (brk === "webhook-no-nosniff" && path === "/api/v1/baymax/wa/webhook") drop("X-Content-Type-Options");
  if (brk === "page-referrer" && path === "/app/") h["Referrer-Policy"] = "no-referrer";
  if (brk === "page-no-csp" && path === "/app/") drop("Content-Security-Policy");
  if (brk === "root-no-hsts" && path === "/") drop("Strict-Transport-Security");
  if (brk === "next-no-hsts" && path === "/app/healthz") drop("Strict-Transport-Security");
  if (brk === "next-xfo-sameorigin" && path === "/app/healthz") h["X-Frame-Options"] = "SAMEORIGIN";
  if (brk === "next-referrer" && path === "/app/healthz") h["Referrer-Policy"] = "no-referrer";
  // appended rather than replaced: the last value is the right one, but the browser receives both
  if (brk === "next-xfo-duplicate" && path === "/app/healthz") h["X-Frame-Options"] = ["SAMEORIGIN", "DENY"];
  if (brk === "page-referrer-duplicate" && path === "/app/") h["Referrer-Policy"] = ["no-referrer", "strict-origin-when-cross-origin"];
  return h;
}

function send(res, path, status, type, body, csp = JAVA_CSP, extra = {}) {
  res.writeHead(status, { ...headers(path, csp), "Content-Type": type, ...extra });
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (hang) return; // accepted, never answered
  const path = req.url;
  const html = (s) => `<!doctype html><html><body>${s}</body></html>`;
  switch (path) {
    case "/app/healthz": {
      if (flaky > 0) { flaky--; return send(res, path, 502, "text/plain", "bad gateway"); }
      if (brk === "healthz-java") {
        return send(res, path, 404, "application/json", '{"userMessage":"Invalid request","statusCode":400}');
      }
      let body = '{"service":"medioo-web","sha":"9e1d2c4","build":"9e1d2c4"}';
      if (brk === "healthz-service") body = '{"service":"medscribe-ai","sha":"9e1d2c4"}';
      // a nested medioo-web BEFORE the top-level field: a first-match grep would pass this
      if (brk === "healthz-service-nested") body = '{"upstream":{"service":"medioo-web"},"service":"medscribe-ai"}';
      if (brk === "healthz-not-json") body = "<html>medioo-web</html>";
      return send(res, path, 200, "application/json", body, brk === "healthz-no-nonce" ? JAVA_CSP : NEXT_CSP,
        { "Cache-Control": "no-store" });
    }
    case "/actuator/health": {
      let body = '{"status":"UP","components":{"diskSpace":{"status":"UP","details":{"path":"/app/.","free":5452595,"marker":"DISK-DETAIL-NEVER-PRINTED"}}}}';
      // the components say UP before the top-level DOWN: a first-match grep would pass this
      if (brk === "health-down") body = '{"components":{"r2dbc":{"status":"UP"}},"status":"DOWN"}';
      return send(res, path, brk === "health-503" ? 503 : 200, "application/vnd.spring-boot.actuator.v3+json", body);
    }
    case "/app/":
      return send(res, path, 200, "text/html",
        brk === "app-no-field" ? html('<p>whatsapp_number</p><input name="phone">') : html('<form><input name="whatsapp_number" type="tel"></form>'));
    case "/app":
      return send(res, path, 200, "text/html", html('<input name="whatsapp_number">'));
    case "/":
      return send(res, path, brk === "root-500" ? 500 : 200, "text/html", html("Medioo"));
    case "/medscribeai":
      return send(res, path, brk === "medscribe-404" ? 404 : 200, "text/html", html("MedScribe"));
    case "/baymax":
      return send(res, path, brk === "baymax-302" ? 302 : 301, "text/plain", "",
        JAVA_CSP, { Location: brk === "baymax-location" ? "/" : "/app" });
    case "/baymax/":
      return send(res, path, 301, "text/plain", "", JAVA_CSP, { Location: brk === "baymax-slash-location" ? "/" : "/app/" });
    case "/medscribeai/":
      return send(res, path, brk === "medscribe-slash-404" ? 404 : 200, "text/html", html("MedScribe"));
    // not routed yet: Java's JSON 404, as production answered on 2026-10-21; Next's own 404 is an HTML page
    case "/_next/static/x.js":
      if (brk === "next-static-routed") return send(res, path, 404, "text/html", html("404: This page could not be found."), NEXT_CSP);
      return send(res, path, 404, "application/json", '{"userMessage":"Invalid request","statusCode":400,"data":null}');
    // the old hostname's Caddy block: a bare redirect, no header block
    case "/old-host/app/home":
      res.writeHead(301, { Location: brk === "old-host-location" ? "https://medioo.eliooo.org/" : "https://medioo.eliooo.org/app/home" });
      return res.end();
    case "/api/v1/baymax/wa/webhook":
      return send(res, path, brk === "webhook-200" ? 200 : 403, "text/plain;charset=UTF-8", "");
    default:
      return send(res, path, 404, "text/plain", "not found");
  }
});
server.listen(0, "127.0.0.1", () => fs.writeFileSync(process.env.STUB_PORT_FILE, String(server.address().port)));
STUB

# start_stub [env...]: (re)starts the stub with the given env, sets BASE
start_stub() {
  [ -n "$STUB_PID" ] && { kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; }
  rm -f "$WORK/port"
  env STUB_PORT_FILE="$WORK/port" "$@" node "$WORK/stub.cjs" &
  STUB_PID=$!
  for _ in $(seq 1 50); do [ -s "$WORK/port" ] && break; sleep 0.1; done
  [ -s "$WORK/port" ] || { echo "FAIL: the stub server did not start"; exit 1; }
  BASE="http://127.0.0.1:$(cat "$WORK/port")"
}

# smoke [env...]: runs smoke-https.sh against $BASE, one try, output to $WORK/out.log; returns its exit code
smoke() {
  CASES=$((CASES + 1))
  env SMOKE_TRIES=1 SMOKE_SLEEP=0 SMOKE_OLD_BASE="$BASE/old-host" "$@" bash "$SMOKE" "$BASE" > "$WORK/out.log" 2>&1
}
out() { tr '\n' '|' < "$WORK/out.log" | cut -c1-600; }
fails() { grep -c '^FAIL ' "$WORK/out.log"; }
passes() { grep -c '^PASS ' "$WORK/out.log"; }

# 1. the flipped table: every row passes, and there are exactly ROWS of them
start_stub
if smoke; then :; else fail "flipped: exited non-zero on a healthy table: $(out)"; fi
[ "$(passes)" = "$ROWS" ] || fail "flipped: $(passes) PASS rows, expected $ROWS: $(out)"
[ "$(fails)" = 0 ] || fail "flipped: $(fails) FAIL rows: $(out)"
grep -q 'DISK-DETAIL-NEVER-PRINTED' "$WORK/out.log" && fail "flipped: a response body reached the output"

# 2. today (before the flip): Java answers /app/healthz with its 404; that row fails, and only that row
start_stub STUB_BREAK=healthz-java
if smoke; then fail "today: exited 0 although /app/healthz is Java's 404: $(out)"; fi
grep -qE '^FAIL /app/healthz .*404' "$WORK/out.log" || fail "today: no 'FAIL /app/healthz ... 404' line: $(out)"
[ "$(fails)" = 1 ] || fail "today: $(fails) FAIL rows, expected 1 (/app/healthz alone): $(out)"
[ "$(passes)" = $((ROWS - 1)) ] || fail "today: $(passes) PASS rows, expected $((ROWS - 1)): $(out)"

# 3. one break at a time: <break>:<row path>:<text the FAIL line must carry>
while IFS='|' read -r brk row why; do
  [ -n "$brk" ] || continue
  start_stub STUB_BREAK="$brk"
  if smoke; then fail "$brk: exited 0: $(out)"; continue; fi
  line=$(grep -E "^FAIL $row " "$WORK/out.log" | head -1)
  [ -n "$line" ] || { fail "$brk: no 'FAIL $row' line: $(out)"; continue; }
  [[ "$line" == *"$why"* ]] || fail "$brk: the FAIL line does not say [$why]: $line"
  [ "$(fails)" = 1 ] || fail "$brk: $(fails) FAIL rows, expected exactly 1 ($row): $(out)"
done <<'TABLE'
healthz-service|/app/healthz|service is [medscribe-ai], expected [medioo-web]
healthz-service-nested|/app/healthz|service is [medscribe-ai], expected [medioo-web]
healthz-not-json|/app/healthz|service is [], expected [medioo-web]
healthz-no-nonce|/app/healthz|content-security-policy has no nonce
next-no-hsts|/app/healthz|strict-transport-security is missing
next-xfo-sameorigin|/app/healthz|x-frame-options is [SAMEORIGIN], expected [DENY]
next-xfo-duplicate|/app/healthz|x-frame-options is sent 2 times, expected once
next-referrer|/app/healthz|referrer-policy is [no-referrer], expected [strict-origin-when-cross-origin]
health-down|/actuator/health|status is [DOWN], expected [UP]
health-503|/actuator/health|HTTP 503, expected 200
api-no-xfo|/actuator/health|x-frame-options is [], expected [DENY]
app-no-field|/app/|no name="whatsapp_number"
page-referrer|/app/|referrer-policy is [no-referrer], expected [strict-origin-when-cross-origin]
page-no-csp|/app/|content-security-policy is missing
page-referrer-duplicate|/app/|referrer-policy is sent 2 times, expected once
root-500|/|HTTP 500, expected 200
root-no-hsts|/|strict-transport-security is missing
medscribe-404|/medscribeai|HTTP 404, expected 200
medscribe-slash-404|/medscribeai/|HTTP 404, expected 200
baymax-302|/baymax|HTTP 302, expected 301
baymax-location|/baymax|location is [/], expected [/app]
baymax-slash-location|/baymax/|location is [/], expected [/app/]
webhook-200|/api/v1/baymax/wa/webhook|HTTP 200, expected 403
webhook-no-nosniff|/api/v1/baymax/wa/webhook|x-content-type-options is [], expected [nosniff]
next-static-routed|/_next/static/x.js|statusCode is [], expected [400]
old-host-location|http://127.0.0.1:[0-9]+/old-host/app/home|location is [https://medioo.eliooo.org/], expected [https://medioo.eliooo.org/app/home]
TABLE

# 4. a transient 502 (Caddy before its upstream answers) is retried until the table passes
start_stub STUB_FLAKY=2
if smoke SMOKE_TRIES=3; then :; else fail "flaky: two 502s then healthy, 3 tries, still failed: $(out)"; fi
[ "$(passes)" = "$ROWS" ] || fail "flaky: the final table has $(passes) PASS rows, expected $ROWS: $(out)"
grep -q 'retrying' "$WORK/out.log" || fail "flaky: passed without retrying, so the 502 was never seen: $(out)"

# 5. nothing listening: every row fails, quickly, with status 000
kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; STUB_PID=""
START=$SECONDS
if smoke; then fail "unreachable: exited 0 with nothing listening: $(out)"; fi
[ "$(fails)" = "$ROWS" ] || fail "unreachable: $(fails) FAIL rows, expected $ROWS: $(out)"
grep -qE '^FAIL /actuator/health .*HTTP 000' "$WORK/out.log" || fail "unreachable: no 'HTTP 000' line: $(out)"
[ $((SECONDS - START)) -lt 30 ] || fail "unreachable: took $((SECONDS - START))s"

# 6. a host that accepts and never answers (a stalled Caddy): fails within SMOKE_DEADLINE, every try and row included,
#    not after tries x rows x --max-time (10 x 11 x 15 s, over 25 minutes inside the deploy's concurrency group).
#    A watchdog kills a copy with no deadline, so the suite itself cannot hang.
start_stub STUB_HANG=1
CASES=$((CASES + 1))
START=$SECONDS
env SMOKE_TRIES=10 SMOKE_SLEEP=1 SMOKE_DEADLINE=6 SMOKE_OLD_BASE="$BASE/old-host" bash "$SMOKE" "$BASE" > "$WORK/out.log" 2>&1 &
SMOKE_PID=$!
( sleep 40; pkill -P "$SMOKE_PID"; kill "$SMOKE_PID" ) >/dev/null 2>&1 &
WATCHDOG=$!
wait "$SMOKE_PID"; rc=$?
kill "$WATCHDOG" 2>/dev/null; wait "$WATCHDOG" 2>/dev/null
ELAPSED=$((SECONDS - START))
[ "$rc" != 0 ] || fail "hang: exited 0 against a host that never answers: $(out)"
[ "$ELAPSED" -le 12 ] || fail "hang: took ${ELAPSED}s with SMOKE_DEADLINE=6 (killed at 40 s: $([ "$rc" -ge 128 ] && echo yes || echo no)): $(out)"
grep -qF 'deadline' "$WORK/out.log" || fail "hang: the output does not say the deadline was reached: $(out)"
kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; STUB_PID=""

# 7. no base URL: usage, exit 2
CASES=$((CASES + 1))
bash "$SMOKE" > "$WORK/out.log" 2>&1; rc=$?
[ "$rc" = 2 ] || fail "usage: exit $rc with no base URL, expected 2: $(out)"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "smoke-https: $CASES cases passed"
