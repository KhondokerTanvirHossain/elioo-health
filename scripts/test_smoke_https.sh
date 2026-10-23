#!/usr/bin/env bash
#
# Tests for scripts/smoke-https.sh (plan W1c), against a local stub server (node, written below) that answers the
# path-class table the way production answers it after the F3 flip (plan Wave 3, Task F: GET/HEAD /app, /app/ and
# /app/verify on medioo-web, on top of F0's preview path and assets), as measured on 2026-10-23. Real curl, real HTTP,
# no network beyond 127.0.0.1. The stub answers by method and path, so a row sent with the wrong method gets another
# row's answer.
#
#   bash scripts/test_smoke_https.sh
#   SMOKE_SH=/path/to/copy.sh bash scripts/test_smoke_https.sh   # replays the same cases against another copy
#
# What is under test, in both directions:
# - the flipped table passes, every row, and the row count is the table's (a smoke that checks nothing cannot pass);
# - the table before F0 (Java answering the preview path and the three asset prefixes with its JSON 404, and /app/add
#   with its own 303), fails on exactly those six rows and nothing else; each of them, alone, fails with Java's answer (both directions per row);
# - today's table, before F3 (Java answering GET /app, /app/ and /app/verify with its own sign-in pages, and HEAD on
#   them with its JSON 404), fails on exactly the five new Next rows and nothing else, each saying why; each of them,
#   alone, fails with Java's answer (both directions per row);
# - the F3 rows that stay Java's (S1's fallback /app/basic and its language switch, S3's POST /app/login and
#   POST /app/verify, and GET /app/login and /app/verifyx just outside the sign-in matcher) pass on Java's answer and
#   fail on Next's, each naming the row;
# - a row's method is the one sent (the stub logs every request, and answers HEAD and POST apart from GET), a `PROBE`
#   row is never sent by this script (aProbeRowIsNeverSent: the route probe's alone), and a production row changes no
#   data (aProductionRowChangesNoData): what the stub actually received from the table is GET, HEAD and the two
#   sign-in POSTs alone, and both POSTs are still sent; every route in Java's snapshot docs/infra/app-routes.txt whose
#   method is not GET (less those two), and methods the snapshot does not hold (DELETE /app/api/family, PATCH, a
#   lower-case method), planted as a production row one at a time, is refused with exit 2, named, before anything at
#   all is sent;
# - each row, broken alone, fails that row and only that row: status, redirect target, the JSON field (parsed, not
#   grepped: a nested "status":"UP" or "service":"medioo-web" before the top-level one must not pass), the HTML
#   marker, and each security header on each path class (the nonce CSP on the Next class, the sign-in pages included;
#   a Java CSP on the page class, S1's /app/basic, the one Java sign-in page left); every row has a break;
# - a security header sent twice fails, whatever the last value says (a browser sees both: conflicting
#   X-Frame-Options are ignored, so SAMEORIGIN then DENY allows framing);
# - routing stopped at the F0 paths, in both directions: Java still owns the API (/app/api/me answered by Next fails),
#   and the paths just outside each new matcher are still Java's JSON 404 (/app/previewx, outside the preview matcher;
#   /_next/image, outside /_next/static/*; /app/preview/probe, excluded because the budget probe never runs the preview
#   gate): Java's answer passes each, and Next's answer fails each, naming the row. A missing chunk is told apart by its
#   Content-Type, since Java's JSON 404 carries the same status and the same four headers;
# - the static class needs no CSP (the hashed chunks skip medioo-web's middleware) but still needs the four headers,
#   each once;
# - the old hostname still redirects every path to the new one (DR-15: links already sent to families);
# - F7 (plan Wave 3, Task F): GET /app/add is medioo-web's, signed out a 307 to the sign-in page holding /app/add;
#   Java's answer there before the flip (303 to /app/, its own CSP) fails that row and only that row; /app/add/ just
#   outside the exact matcher passes on Java's JSON 404 and fails on Next's HTML 404 (`add-slash-next`);
# - a PROBE row is never sent by the production run (aProductionRunNeverSendsAProbeRow): POST /app/add is Java's
#   upload and must not be pressed against production by a smoke run; the stub's request log is read, not the output;
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
# the table's size: /app/healthz /actuator/health /app/api/me /app/ /app, HEAD /app/, /app/verify, HEAD /app/verify,
# /app/login /app/verifyx /app/basic /lang/en?back=..., POST /app/verify, POST /app/login, / /medscribeai /medscribeai/
# /baymax /baymax/ webhook /_next/static/x.js /fonts/noto-sans-latin.woff2 /brand/mio-head.webp /brand/medioo-light.svg
# (P3) /app/preview /app/preview/ /app/previewx /_next/image /app/preview/probe /app/add /app/add/ (F7) and the old
# host's /app/home. The PROBE rows (`PROBE HEAD /app/add`, `PROBE POST /app/add`) are not counted: the production run
# never sends them (caddy-route-probe.sh does).
ROWS=32

fail() { echo "FAIL: $1"; FAILED=1; }

cat > "$WORK/stub.cjs" <<'STUB'
// The production table, served on 127.0.0.1. STUB_BREAK names one thing to break; STUB_FLAKY=<n> makes the first
// <n> requests to /app/healthz answer 502 (Caddy before the upstream is up); STUB_HANG=1 accepts every request and
// never answers (a Caddy that stalls). /old-host/* stands in for https://baymax.eliooo.org/* (SMOKE_OLD_BASE).
// Answers are keyed by "<METHOD> <path>" for anything but GET, so HEAD and POST rows get their own answers; every
// request is appended to STUB_LOG as "<METHOD> <path>", so a case can say what was (and was not) sent.
const http = require("http");
const fs = require("fs");
const brk = process.env.STUB_BREAK || "";
const log = process.env.STUB_LOG || "";
const hang = process.env.STUB_HANG === "1";
let flaky = Number(process.env.STUB_FLAKY || 0);
const JAVA_CSP = "default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' https: http: blob:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
const NEXT_CSP = "default-src 'none'; script-src 'self' 'nonce-c3R1Yk5vbmNlMTIzNDU2Nw=='; style-src 'self'; img-src 'self' https: http: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'";

function headers(path, csp) { // path: the request's key, "<METHOD> <path>" for anything but GET
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
  // the page class (Java's HTML pages: S1's /app/basic is the one sign-in page left on Java) and the sign-in pages
  // Next serves from F3 (class next), each with its own header breaks
  if (brk === "page-referrer" && path === "/app/basic") h["Referrer-Policy"] = "no-referrer";
  if (brk === "page-no-csp" && path === "/app/basic") drop("Content-Security-Policy");
  if (brk === "signin-referrer" && path === "/app/") h["Referrer-Policy"] = "no-referrer";
  if (brk === "signin-no-csp" && path === "/app/") drop("Content-Security-Policy");
  if (brk === "root-no-hsts" && path === "/") drop("Strict-Transport-Security");
  if (brk === "next-no-hsts" && path === "/app/healthz") drop("Strict-Transport-Security");
  if (brk === "next-xfo-sameorigin" && path === "/app/healthz") h["X-Frame-Options"] = "SAMEORIGIN";
  if (brk === "next-referrer" && path === "/app/healthz") h["Referrer-Policy"] = "no-referrer";
  // appended rather than replaced: the last value is the right one, but the browser receives both
  if (brk === "next-xfo-duplicate" && path === "/app/healthz") h["X-Frame-Options"] = ["SAMEORIGIN", "DENY"];
  if (brk === "page-referrer-duplicate" && path === "/app/basic") h["Referrer-Policy"] = ["no-referrer", "strict-origin-when-cross-origin"];
  if (brk === "signin-referrer-duplicate" && path === "/app/") h["Referrer-Policy"] = ["no-referrer", "strict-origin-when-cross-origin"];
  if (brk === "static-no-hsts" && path === "/_next/static/x.js") drop("Strict-Transport-Security");
  if (brk === "static-xfo-duplicate" && path === "/fonts/noto-sans-latin.woff2") h["X-Frame-Options"] = ["SAMEORIGIN", "DENY"];
  if (brk === "static-no-nosniff" && path === "/brand/mio-head.webp") drop("X-Content-Type-Options");
  if (brk === "preview-no-nonce" && path === "/app/preview") h["Content-Security-Policy"] = JAVA_CSP;
  return h;
}

// csp null: no Content-Security-Policy at all (Next's hashed chunks, which skip its middleware)
function send(res, path, status, type, body, csp = JAVA_CSP, extra = {}) {
  const h = headers(res.key, csp);
  if (csp === null) delete h["Content-Security-Policy"];
  res.writeHead(status, { ...h, "Content-Type": type, ...extra });
  res.end(body);
}

// Java's answer to a path it does not serve, exactly as production gave it on 2026-10-22 before F0 (the five F0 rows)
const JAVA_404 = '{"userMessage":"Invalid request","statusCode":400,"data":null,"error":{"code":"1002","message":"Invalid request"}}';
// before F0, every F0 path is Java's, and so is /app/add (F7); each <row>-java break makes one of them Java's alone
const javaOwns = (row) => brk === "before-f0" || brk === `${row}-java`;
// before F3 (production until the F3 box edit), GET and HEAD /app, /app/ and /app/verify are Java's
const javaSignIn = (row) => brk === "before-f3" || brk === `${row}-java`;
// Next's answer to a path it does not serve, and the two number pages, each as production or the replica gave it
const NEXT_404 = "<!DOCTYPE html><html><body>404: This page could not be found.</body></html>";
const JAVA_NUMBER = '<form method="post" action="/app/login"><input id="n" type="tel" name="whatsapp_number"></form>';
const NEXT_NUMBER = '<form class="sign-in__form"><input id="whatsapp-number" type="tel" name="whatsapp_number"></form>';

const server = http.createServer((req, res) => {
  if (hang) return; // accepted, never answered
  const path = req.url;
  const key = req.method === "GET" ? path : `${req.method} ${path}`;
  res.key = key;
  if (log) fs.appendFileSync(log, `${req.method} ${path}\n`);
  const html = (s) => `<!doctype html><html><body>${s}</body></html>`;
  switch (key) {
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
    // F3: the sign-in pages are Next's (measured 2026-10-23 through a replica of the deployed sha, signed out): the
    // number step is 200 under medioo-web's nonce CSP, and the code step with no pending login is Next's 307 to /app/.
    // Before F3, Java served GET with its own number page (its CSP, no nonce) and 303, and HEAD with its JSON 404.
    case "/app/":
      if (javaSignIn("app-slash")) return send(res, path, 200, "text/html", html(JAVA_NUMBER));
      return send(res, path, 200, "text/html; charset=utf-8",
        brk === "app-no-field" ? html('<p>whatsapp_number</p><input name="phone">') : html(NEXT_NUMBER), NEXT_CSP);
    case "/app":
      if (javaSignIn("app")) return send(res, path, 200, "text/html", html(JAVA_NUMBER));
      return send(res, path, 200, "text/html; charset=utf-8", html(NEXT_NUMBER), NEXT_CSP);
    case "HEAD /app/":
      if (javaSignIn("head-app-slash")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 200, "text/html; charset=utf-8", "", NEXT_CSP);
    case "/app/verify":
      if (javaSignIn("verify")) return send(res, path, 303, "text/plain", "", JAVA_CSP, { Location: "/app/" });
      return send(res, path, 307, "text/html; charset=utf-8", "", NEXT_CSP,
        { Location: brk === "verify-location" ? "/app/?next=%2Fapp%2Fverify" : "/app/" });
    case "HEAD /app/verify":
      if (javaSignIn("head-verify")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 307, "text/html; charset=utf-8", "", NEXT_CSP, { Location: "/app/" });
    // Just outside the sign-in matcher, still Java's JSON 404: GET /app/login (S3: /app/login has no GET and is never
    // routed) and /app/verifyx (`path /app/verify*` would take it). Next's answer to either is its HTML 404.
    case "/app/login":
      if (brk === "login-next") return send(res, path, 404, "text/html; charset=utf-8", NEXT_404, NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    case "/app/verifyx":
      if (brk === "verifyx-next") return send(res, path, 404, "text/html; charset=utf-8", NEXT_404, NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    // S1: the no-JS fallback stays Java's, and its language switch on the code step lands back on it (PR #136)
    case "/app/basic":
      if (brk === "basic-next") return send(res, path, 404, "text/html; charset=utf-8", NEXT_404, NEXT_CSP);
      return send(res, path, 200, "text/html",
        html(`<form method="post" action="${brk === "basic-action" ? "/app/login" : "/app/basic/login"}"><input name="whatsapp_number"></form>`));
    case "/lang/en?back=%2Fapp%2Fbasic%2Fverify":
      return send(res, path, 303, "text/plain", "", JAVA_CSP,
        { Location: brk === "lang-back" ? "/app/basic/" : "/app/basic/verify" });
    // S3: the sign-in POSTs stay Java's (the flip is GET/HEAD only). With no login cookie Java's code step sends the
    // visitor to the number step, and an empty number form is the number page again, with its error. Next would answer
    // the first with its page's 307 and the second with its 404 (it has no /app/login).
    case "POST /app/verify":
      if (brk === "post-verify-next") return send(res, path, 307, "text/html; charset=utf-8", "", NEXT_CSP, { Location: "/app/" });
      return send(res, path, 303, "text/plain", "", JAVA_CSP, { Location: "/app/" });
    case "POST /app/login":
      if (brk === "post-login-next") return send(res, path, 404, "text/html; charset=utf-8", NEXT_404, NEXT_CSP);
      return send(res, path, 200, "text/html", html(JAVA_NUMBER));
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
    // F0's assets, as medioo-web answers them behind Caddy (measured 2026-10-22): a missing chunk is Next's plain-text
    // 404 with no CSP (the middleware skips /_next/static), the fonts and brand files are 200 with medioo-web's CSP
    case "/_next/static/x.js":
      if (javaOwns("static")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 404, "text/plain; charset=utf-8", "Not Found", null);
    case "/fonts/noto-sans-latin.woff2":
      if (javaOwns("fonts")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 200, brk === "fonts-type" ? "text/html" : "font/woff2", "wOF2", NEXT_CSP);
    case "/brand/mio-head.webp":
      if (javaOwns("brand")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 200, "image/webp", "RIFFWEBP", NEXT_CSP);
    // PO ruling P3: medioo-web sends its bodies unencoded and Caddy's `encode zstd gzip` encodes them, in zstd when
    // the request offers it and only then (a request that offers nothing gets the plain body, as from Caddy). Before
    // P3, medioo-web gzipped the svg itself and Caddy passed that on.
    case "/brand/medioo-light.svg": {
      if (javaOwns("brand-svg")) return send(res, path, 404, "application/json", JAVA_404);
      const offered = String(req.headers["accept-encoding"] || "");
      let encoding = null;
      if (brk === "before-p3" && /gzip/.test(offered)) encoding = "gzip";
      else if (brk !== "brand-svg-identity" && /zstd/.test(offered)) encoding = "zstd";
      const extra = encoding ? { "Content-Encoding": encoding, Vary: "Accept-Encoding" } : {};
      if (brk === "brand-svg-no-hsts") {
        const h = headers(path, NEXT_CSP);
        delete h["Strict-Transport-Security"];
        res.writeHead(200, { ...h, "Content-Type": "image/svg+xml", ...extra });
        return res.end("<svg/>");
      }
      return send(res, path, 200, "image/svg+xml", "<svg/>", NEXT_CSP, extra);
    }
    // the preview path, signed out: the preview gate's 401 from `me` is the sign-in page holding the preview
    case "/app/preview":
      if (javaOwns("preview")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 307, "text/html", "", NEXT_CSP,
        { Location: brk === "preview-location" ? "/app/" : "/app/?next=%2Fapp%2Fpreview" });
    case "/app/preview/":
      if (javaOwns("preview-slash")) return send(res, path, 404, "application/json", JAVA_404);
      return send(res, path, 307, "text/html", "", NEXT_CSP, { Location: "/app/?next=%2Fapp%2Fpreview%2F" });
    // Just outside the F0 matchers, still Java's (measured on production 2026-10-22: Java's JSON 404 on each). A matcher
    // that reached too far (`/app/preview*`, `/_next/*`, the preview block without its probe exclusion) gives Next's
    // answer: its HTML 404 for the first two, and the budget probe's 200 page for the third.
    case "/app/previewx":
      if (brk === "previewx-next") return send(res, path, 404, "text/html; charset=utf-8", html("404: This page could not be found."), NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    case "/_next/image":
      if (brk === "next-image-next") return send(res, path, 404, "text/html; charset=utf-8", html("404: This page could not be found."), NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    case "/app/preview/probe":
      if (brk === "preview-probe-next") return send(res, path, 200, "text/html; charset=utf-8", html('<nav class="tabs"><a href="/app/probe">x</a></nav>'), NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    // F7: the add sheet (plan W7) as medioo-web answers it signed out (measured 2026-10-23 against the deployed
    // sha's build): the shell reads `me`, gets the 401 and holds the place. Before the flip Java answered GET with its
    // session filter's 303 to /app/ (production, 2026-10-23). A POST is Java's upload, never Next's.
    case "/app/add":
      if (req.method === "POST" || javaOwns("add")) return send(res, path, 303, "text/html", "", JAVA_CSP, { Location: "/app/" });
      return send(res, path, 307, "text/html", "", NEXT_CSP,
        { Location: brk === "add-location" ? "/app/" : "/app/?next=%2Fapp%2Fadd" });
    // Just outside F7's exact `path /app/add`, still Java's (production, 2026-10-23: Java's JSON 404). A matcher
    // widened to `/app/add*` gives Next's HTML 404.
    case "/app/add/":
      if (brk === "add-slash-next") return send(res, path, 404, "text/html; charset=utf-8", html("404: This page could not be found."), NEXT_CSP);
      return send(res, path, 404, "application/json", JAVA_404);
    // Java owns the API: a Caddy route that sent /app/api/* to Next would answer Next's HTML 404 here
    case "/app/api/me":
      if (brk === "me-next") return send(res, path, 404, "text/html", html("404: This page could not be found."), NEXT_CSP);
      return send(res, path, 401, "application/json", brk === "me-reason" ? '{"reason":"NOT_FOUND"}' : '{"reason":"UNAUTHENTICATED"}');
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
  : > "$WORK/requests.log"
  env STUB_PORT_FILE="$WORK/port" STUB_LOG="$WORK/requests.log" "$@" node "$WORK/stub.cjs" &
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
# the boundary rows pass on Java's JSON 404 (the other direction, Next's answer failing each, is in the break table)
for row in /app/previewx /_next/image /app/preview/probe /app/add/; do
  grep -qxF "PASS $row HTTP 404" "$WORK/out.log" || fail "flipped: no 'PASS $row HTTP 404' line on Java's answer: $(out)"
done
# aProductionRowChangesNoData, read from what was sent rather than from the table's text: every request the production
# table made is GET or HEAD, or exactly one of the two sign-in POSTs; and those two are still sent (the other direction)
sent=$(grep -vE '^(GET|HEAD) ' "$WORK/requests.log" | grep -vxE 'POST /app/(login|verify)' || true)
[ -z "$sent" ] || fail "aProductionRowChangesNoData: the production table sent [$(printf '%s' "$sent" | tr '\n' ';')]"
for req in "POST /app/login" "POST /app/verify"; do
  [ "$(grep -cxF "$req" "$WORK/requests.log")" = 1 ] \
    || fail "aProductionRowChangesNoData: [$req] was not sent exactly once: $(tr '\n' '|' < "$WORK/requests.log" | cut -c1-400)"
done

# 2a. before F0: Java answers the preview path and the three asset prefixes with its JSON 404 (P3's svg row among
#    them), and /app/add with its session filter's 303 to /app/ (F7's row); those seven rows fail, each saying why, and
#    every other row passes
start_stub STUB_BREAK=before-f0
if smoke; then fail "today: exited 0 although the F0 paths are Java's 404: $(out)"; fi
while IFS='|' read -r row why; do
  grep -E "^FAIL $row " "$WORK/out.log" | grep -qF -- "$why" || fail "today: no 'FAIL $row ... $why' line: $(out)"
done <<'TODAY'
/_next/static/x.js|content-type is [application/json], expected [text/plain]
/fonts/noto-sans-latin.woff2|HTTP 404, expected 200
/brand/mio-head.webp|HTTP 404, expected 200
/brand/medioo-light.svg|HTTP 404, expected 200
/app/preview|HTTP 404, expected 307
/app/preview/|HTTP 404, expected 307
/app/add|HTTP 303, expected 307
TODAY
[ "$(fails)" = 7 ] || fail "today: $(fails) FAIL rows, expected 7 (the F0 and F7 rows alone): $(out)"
[ "$(passes)" = $((ROWS - 7)) ] || fail "today: $(passes) PASS rows, expected $((ROWS - 7)): $(out)"

# 2b. today (before F3, with F0 and F7 on the box): Java answers GET /app, /app/ and /app/verify with its own sign-in pages and HEAD on them with
#    its JSON 404; the five Next sign-in rows fail, each saying why, and every other row passes
start_stub STUB_BREAK=before-f3
if smoke; then fail "today: exited 0 although the sign-in pages are Java's: $(out)"; fi
while IFS='|' read -r row why; do
  grep -E "^FAIL $row " "$WORK/out.log" | grep -qF -- "$why" || fail "today: no 'FAIL $row ... $why' line: $(out)"
done <<'TODAY'
/app/|content-security-policy has no nonce
/app|content-security-policy has no nonce
HEAD /app/|HTTP 404, expected 200
/app/verify|HTTP 303, expected 307
HEAD /app/verify|HTTP 404, expected 307
TODAY
[ "$(fails)" = 5 ] || fail "today: $(fails) FAIL rows, expected 5 (the F3 Next rows alone): $(out)"
[ "$(passes)" = $((ROWS - 5)) ] || fail "today: $(passes) PASS rows, expected $((ROWS - 5)): $(out)"
# each row asked with its own method: the stub saw HEAD and POST, never a GET standing in for them
for req in "HEAD /app/" "HEAD /app/verify" "POST /app/verify" "POST /app/login"; do
  grep -qxF "$req" "$WORK/requests.log" || fail "today: the stub never received [$req]: $(tr '\n' '|' < "$WORK/requests.log" | cut -c1-400)"
done

# 2c. before P3 (PO ruling 2026-10-23): medioo-web gzipped its own responses, so Caddy passed them on as they were. The
#     encoding row, which asks for zstd or gzip, sees gzip where only Caddy answers zstd; it fails alone, saying so.
start_stub STUB_BREAK=before-p3
if smoke; then fail "before-p3: exited 0 although medioo-web encoded its own asset: $(out)"; fi
grep -E "^FAIL /brand/medioo-light.svg " "$WORK/out.log" | grep -qF -- "content-encoding is [gzip], expected [zstd]" \
  || fail "before-p3: no 'FAIL /brand/medioo-light.svg ... content-encoding is [gzip], expected [zstd]' line: $(out)"
[ "$(fails)" = 1 ] || fail "before-p3: $(fails) FAIL rows, expected exactly 1 (the encoding row): $(out)"

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
healthz-java|/app/healthz|HTTP 404, expected 200
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
page-referrer|/app/basic|referrer-policy is [no-referrer], expected [strict-origin-when-cross-origin]
page-no-csp|/app/basic|content-security-policy is missing
page-referrer-duplicate|/app/basic|referrer-policy is sent 2 times, expected once
signin-referrer|/app/|referrer-policy is [no-referrer], expected [strict-origin-when-cross-origin]
signin-no-csp|/app/|content-security-policy is missing
signin-referrer-duplicate|/app/|referrer-policy is sent 2 times, expected once
root-500|/|HTTP 500, expected 200
root-no-hsts|/|strict-transport-security is missing
medscribe-404|/medscribeai|HTTP 404, expected 200
medscribe-slash-404|/medscribeai/|HTTP 404, expected 200
baymax-302|/baymax|HTTP 302, expected 301
baymax-location|/baymax|location is [/], expected [/app]
baymax-slash-location|/baymax/|location is [/], expected [/app/]
webhook-200|/api/v1/baymax/wa/webhook|HTTP 200, expected 403
webhook-no-nosniff|/api/v1/baymax/wa/webhook|x-content-type-options is [], expected [nosniff]
static-java|/_next/static/x.js|content-type is [application/json], expected [text/plain]
static-no-hsts|/_next/static/x.js|strict-transport-security is missing
fonts-java|/fonts/noto-sans-latin.woff2|HTTP 404, expected 200
fonts-type|/fonts/noto-sans-latin.woff2|content-type is [text/html], expected [font/woff2]
static-xfo-duplicate|/fonts/noto-sans-latin.woff2|x-frame-options is sent 2 times, expected once
brand-java|/brand/mio-head.webp|HTTP 404, expected 200
static-no-nosniff|/brand/mio-head.webp|x-content-type-options is [], expected [nosniff]
brand-svg-java|/brand/medioo-light.svg|HTTP 404, expected 200
brand-svg-identity|/brand/medioo-light.svg|content-encoding is [], expected [zstd]
brand-svg-no-hsts|/brand/medioo-light.svg|strict-transport-security is missing
preview-java|/app/preview|HTTP 404, expected 307
preview-location|/app/preview|location is [/app/], expected [/app/?next=%2Fapp%2Fpreview]
preview-no-nonce|/app/preview|content-security-policy has no nonce
preview-slash-java|/app/preview/|HTTP 404, expected 307
add-java|/app/add|HTTP 303, expected 307
add-location|/app/add|location is [/app/], expected [/app/?next=%2Fapp%2Fadd]
add-slash-next|/app/add/|statusCode is [], expected [400]
app-slash-java|/app/|content-security-policy has no nonce
app-java|/app|content-security-policy has no nonce
head-app-slash-java|HEAD /app/|HTTP 404, expected 200
verify-java|/app/verify|HTTP 303, expected 307
verify-location|/app/verify|location is [/app/?next=%2Fapp%2Fverify], expected [/app/]
head-verify-java|HEAD /app/verify|HTTP 404, expected 307
login-next|/app/login|statusCode is [], expected [400]
verifyx-next|/app/verifyx|statusCode is [], expected [400]
basic-next|/app/basic|HTTP 404, expected 200
basic-action|/app/basic|no action="/app/basic/login"
lang-back|/lang/en\?back=%2Fapp%2Fbasic%2Fverify|location is [/app/basic/], expected [/app/basic/verify]
post-verify-next|POST /app/verify|HTTP 307, expected 303
post-login-next|POST /app/login|HTTP 404, expected 200
previewx-next|/app/previewx|statusCode is [], expected [400]
next-image-next|/_next/image|statusCode is [], expected [400]
preview-probe-next|/app/preview/probe|HTTP 200, expected 404
me-next|/app/api/me|HTTP 404, expected 401
me-reason|/app/api/me|reason is [NOT_FOUND], expected [UNAUTHENTICATED]
old-host-location|http://127.0.0.1:[0-9]+/old-host/app/home|location is [https://medioo.eliooo.org/], expected [https://medioo.eliooo.org/app/home]
TABLE

# 3b. aProductionRunNeverSendsAProbeRow: the table holds a PROBE row (so the case is not vacuous), the stub saw the
#     run's GET /app/add (so its log is read at all), and it saw no POST but the two sign-in POSTs (F3's production
#     rows), nothing to /app/add but that GET, and no request whose path carries the word PROBE
grep -qE '^PROBE POST /app/add\|' "$SMOKE" || fail "probe-skip: the table has no 'PROBE POST /app/add' row, so this case tests nothing"
grep -qE '^PROBE HEAD /app/add\|' "$SMOKE" || fail "probe-skip: the table has no 'PROBE HEAD /app/add' row, so this case tests less than it says"
start_stub
smoke >/dev/null || true
grep -qxF 'GET /app/add' "$WORK/requests.log" || fail "probe-skip: the stub never saw GET /app/add, so its log proves nothing: $(tr '\n' '|' < "$WORK/requests.log" | cut -c1-300)"
if grep -E '^POST ' "$WORK/requests.log" | grep -qvxE 'POST /app/(login|verify)'; then
  fail "probe-skip: the production run sent a POST other than the sign-in POSTs: $(grep -E '^POST ' "$WORK/requests.log" | grep -vxE 'POST /app/(login|verify)' | head -3 | tr '\n' '|')"
fi
if grep -qi 'probe ' "$WORK/requests.log" || grep -cE '^[A-Z]+ /app/add$' "$WORK/requests.log" | grep -qvx 1; then
  fail "probe-skip: a PROBE row reached the host: $(grep -i -e 'probe ' -e '/app/add$' "$WORK/requests.log" | head -3 | tr '\n' '|')"
fi
# a PROBE row would print as a PASS/FAIL line naming it; the summary line names the PROBE rows it left, by count only
grep -qE '^(PASS|FAIL) PROBE ' "$WORK/out.log" && fail "probe-skip: the production run printed a PROBE row: $(out)"
grep -qF ', 2 PROBE rows left to caddy-route-probe.sh,' "$WORK/out.log" || fail "probe-skip: the summary does not count the table's two PROBE rows as left: $(out)"

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

# 8. aProbeRowIsNeverSent: a PROBE row is the route probe's alone (it may POST to a path that changes data); this
#    script neither sends it nor counts it
plant() { # plant <name> <row>: a copy of the script under test with <row> added as the table's first row
  awk -v row="$2" '!done && /^TABLE=\047/ { print "TABLE=\047" row; sub(/^TABLE=\047/, ""); done = 1 } { print }' "$SMOKE" > "$WORK/$1.sh"
  grep -qxF -- "TABLE='$2" "$WORK/$1.sh" || { fail "$1: could not plant [$2] in a copy of $SMOKE"; return 1; }
}
if plant probe-row 'PROBE POST /app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003/delete|303|-|-'; then
  start_stub
  CASES=$((CASES + 1))
  env SMOKE_TRIES=1 SMOKE_SLEEP=0 SMOKE_OLD_BASE="$BASE/old-host" bash "$WORK/probe-row.sh" "$BASE" > "$WORK/out.log" 2>&1 \
    || fail "aProbeRowIsNeverSent: the table with a PROBE row failed: $(out)"
  grep -q '/delete' "$WORK/requests.log" && fail "aProbeRowIsNeverSent: the stub received [$(grep '/delete' "$WORK/requests.log" | head -1)]"
  grep -qF '/delete' "$WORK/out.log" && fail "aProbeRowIsNeverSent: the PROBE row reached the output: $(out)"
  [ "$(passes)" = "$ROWS" ] || fail "aProbeRowIsNeverSent: $(passes) PASS rows, expected $ROWS (a PROBE row is not counted): $(out)"
fi

# 9. aProductionRowChangesNoData: the production run (CI after every deploy and on every retry, and by hand) sends only
#    GET, HEAD and the two harmless sign-in POSTs (POST /app/login, POST /app/verify: SignInPages reads no cookie, no
#    number and calls nothing on them). Any other method on any path must be a PROBE row. The planted rows come from
#    Java's route snapshot (docs/infra/app-routes.txt): every route whose method is not GET, less those two, so a route
#    added later is planted too; plus what the snapshot does not hold, DELETE and PATCH on /app/api and a method in
#    lower case. Each must be refused with exit 2, named, before the stub receives anything at all.
ROUTES="$(cd "$(dirname "$0")/.." && pwd)/docs/infra/app-routes.txt"
planted=$( { grep -E '^[A-Z]+ /' "$ROUTES" | grep -vE '^(GET|HEAD) ' | grep -vxE 'POST /app/(login|verify)' \
               | sed -e 's#{id}#5e9edf6d-0a2a-4913-8675-9d07a4100003#g' -e 's#{[a-z]*}#1#g'
             printf '%s\n' 'DELETE /app/api/family' 'DELETE /app/api/me' 'PATCH /app/api/me' 'post /app/add'; } )
count=$(printf '%s\n' "$planted" | grep -c .)
# guarded against emptiness: the snapshot holds 21 such routes (POST, PUT, DELETE), so at least 25 rows are planted
CASES=$((CASES + 1))
[ "$count" -ge 25 ] || fail "aProductionRowChangesNoData: only $count rows to plant (from $ROUTES); the sweep would pass vacuously"
start_stub
while IFS= read -r row; do
  [ -n "$row" ] || continue
  plant refused-row "$row|303|-|-" || continue
  : > "$WORK/requests.log"
  CASES=$((CASES + 1))
  env SMOKE_TRIES=1 SMOKE_SLEEP=0 SMOKE_OLD_BASE="$BASE/old-host" bash "$WORK/refused-row.sh" "$BASE" > "$WORK/out.log" 2>&1; rc=$?
  [ "$rc" = 2 ] || fail "aProductionRowChangesNoData: a planted '$row' row gave exit $rc, expected 2 (refused): $(out)"
  grep -qF -- "$row" "$WORK/out.log" || fail "aProductionRowChangesNoData: the refusal of '$row' does not name it: $(out)"
  [ -s "$WORK/requests.log" ] \
    && fail "aProductionRowChangesNoData: with '$row' planted the stub received: $(tr '\n' '|' < "$WORK/requests.log" | cut -c1-200)"
done <<< "$planted"
# the other direction: an allowed method planted the same way is sent, not refused
if plant allowed-row 'HEAD /app/healthz|404|-|-'; then
  : > "$WORK/requests.log"
  CASES=$((CASES + 1))
  env SMOKE_TRIES=1 SMOKE_SLEEP=0 SMOKE_OLD_BASE="$BASE/old-host" bash "$WORK/allowed-row.sh" "$BASE" > "$WORK/out.log" 2>&1 \
    || fail "aProductionRowChangesNoData: a planted 'HEAD /app/healthz' row was refused or failed: $(out)"
  grep -qxF 'HEAD /app/healthz' "$WORK/requests.log" || fail "aProductionRowChangesNoData: the planted HEAD row was not sent"
fi
kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; STUB_PID=""

# 7. no base URL: usage, exit 2
CASES=$((CASES + 1))
bash "$SMOKE" > "$WORK/out.log" 2>&1; rc=$?
[ "$rc" = 2 ] || fail "usage: exit $rc with no base URL, expected 2: $(out)"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "smoke-https: $CASES cases passed"
