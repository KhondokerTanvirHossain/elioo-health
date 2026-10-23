// The e2e harness's measuring proxy (PO ruling P3, 2026-10-23). Plain ESM: `node e2e/support/encode-proxy.mjs` runs it
// with no build step, in front of `next start`, as the origin every phone and desktop test talks to.
//
// Why it exists. medioo-web no longer compresses its own responses (next.config.ts: compress: false): Next's streaming
// gzip compressed the RSC-heavy document in tiny flushes, so the 58-value /all page's document was 64 KB on the wire
// where one compression stream gives under 18 KB. In production Caddy's `encode zstd gzip` (docs/infra/Caddyfile.medioo)
// compresses every medioo-web response instead. A `next start` run with nothing in front would measure every body
// unencoded, which no phone ever receives; this proxy encodes the way that Caddy block does, so the suite's DR-42
// budget measures the shipped path. The defining run is still scripts/budget-behind-caddy.sh, behind a real Caddy.
//
// Caddy's rules, each copied from its encode handler (v2.11, defaults; encode.go) and tested in encode-proxy.test.ts:
// - the encoding: the browser's Accept-Encoding weights first, then the order the Caddyfile lists (zstd, then gzip);
// - the decision is made on the FIRST write: encoded when that write is over 512 B or the Content-Length is, never
//   otherwise, whatever follows (MinLength 512);
// - only Caddy's default content types (text/*, JavaScript, JSON, SVG, ...; not woff2, webp, png or jpeg);
// - never a body the upstream already encoded, never under Cache-Control no-transform, never a HEAD, 204 or 304;
// - an encoded body drops Content-Length and Accept-Ranges, adds Vary: Accept-Encoding, and tags a strong ETag;
// - a streamed body (no Content-Length) is flushed after every write, as reverse_proxy flushes a response of unknown
//   length immediately and the encoder flushes with it (a sync flush: the compression window is kept), so streaming
//   still streams and the bytes are what Caddy's flushes cost. A body with a known length is encoded in one pass.
// - levels: Caddy's zstd is Go's klauspost encoder at its default speed, with a 128 KiB window; node has libzstd, here
//   at level 3 (the libzstd default, which klauspost's default speed is modelled on) with the same window. They are
//   not byte-identical: on 2026-10-23, the same build measured through this proxy and through Caddy 2.11.7 gave page
//   totals 0.2% apart (375 to 464 B per page, this proxy the smaller; on W6's RSC-heavy /all page, 978 B the larger),
//   where level 2 was 4% larger. That is why the
//   defining DR-42 run stays behind a real Caddy (scripts/budget-behind-caddy.sh, the same CI job): this proxy makes
//   the suite measure the shipped path, Caddy decides the last few hundred bytes.
// - gzip: Caddy's default level 5 is klauspost's level 5, which compresses less than zlib's level 5. On 2026-10-23
//   the probe's static scripts and stylesheet came to 108,421 B in Caddy 2.11.7's gzip against 108,415 B at node's
//   level 4 and 104,802 B at its level 5, so this proxy gzips at level 4: level 5 made the gzip pass 3.6 KB a page
//   kinder than Caddy, the flattering direction.
// - one header Caddy does not send: a streamed answer carries x-encode-proxy-first-write, the size of the first write
//   the decision was made on, so the budget can apply Caddy's rule to an unencoded streamed body (budget.ts).
// The Host header and everything else the browser sent go to the upstream unchanged, as Caddy's reverse_proxy does.
import { createServer, request } from "node:http";
import { basename } from "node:path";
import { constants, createGzip, createZstdCompress } from "node:zlib";

/** The Caddyfile's order: `encode zstd gzip`. */
const PREFERRED = ["zstd", "gzip"];
const MIN_LENGTH = 512;
/** Added to a streamed answer: the size of its first write (budget.ts reads it as FIRST_WRITE_HEADER, the same name). */
const FIRST_WRITE_HEADER = "x-encode-proxy-first-write";

/** Caddy's default encode matcher (encode.go, Provision): Content-Type prefixes, `*` meaning any suffix. */
const ENCODED_TYPES = [
  "application/atom+xml", "application/eot", "application/font", "application/geo+json", "application/graphql+json",
  "application/graphql-response+json", "application/javascript", "application/json", "application/ld+json",
  "application/manifest+json", "application/opentype", "application/otf", "application/rss+xml", "application/truetype",
  "application/ttf", "application/vnd.api+json", "application/vnd.ms-fontobject", "application/wasm",
  "application/x-httpd-cgi", "application/x-javascript", "application/x-opentype", "application/x-otf",
  "application/x-perl", "application/x-protobuf", "application/x-ttf", "application/xhtml+xml", "application/xml",
  "font/ttf", "font/otf", "image/svg+xml", "image/vnd.microsoft.icon", "image/x-icon", "multipart/bag",
  "multipart/mixed", "text/",
];

/** Whether Caddy's default matcher encodes a response of this Content-Type. */
export function encodesType(contentType) {
  if (!contentType) return false;
  const type = String(contentType).toLowerCase();
  return ENCODED_TYPES.some((prefix) => type.startsWith(prefix));
}

/** The encoding Caddy answers in for this Accept-Encoding, or null for none: weight first, then the Caddyfile order. */
export function chooseEncoding(acceptEncoding) {
  if (!acceptEncoding) return null;
  const offered = new Map();
  for (const part of String(acceptEncoding).split(",")) {
    const [name, ...params] = part.trim().toLowerCase().split(";");
    if (!name) continue;
    const q = params.map((p) => /^\s*q=([0-9.]+)\s*$/.exec(p)).find(Boolean);
    offered.set(name, q ? Number(q[1]) : 1);
  }
  const candidates = PREFERRED.map((name) => ({ name, q: offered.get(name) ?? offered.get("*") ?? 0 })).filter((c) => c.q > 0);
  if (candidates.length === 0) return null;
  // Stable: equal weights keep the Caddyfile's order.
  candidates.sort((a, b) => b.q - a.q);
  return candidates[0].name;
}

function encoder(name) {
  if (name === "zstd") {
    return createZstdCompress({
      params: { [constants.ZSTD_c_compressionLevel]: 3, [constants.ZSTD_c_windowLog]: 17 },
    });
  }
  // Level 4 matches Caddy's level 5 to within a few bytes (the header above).
  return createGzip({ level: 4 });
}

const HOP_BY_HOP = ["connection", "keep-alive", "transfer-encoding", "proxy-connection", "upgrade"];

/** An http.Server (not yet listening) that forwards every request to `upstream` and encodes the answer as Caddy does. */
export function startEncodeProxy({ upstream }) {
  if (typeof createZstdCompress !== "function") {
    throw new Error(`encode-proxy: Node ${process.version} has no zstd (22.15 or later), so it cannot encode as Caddy does`);
  }
  const target = new URL(upstream);
  return createServer((req, res) => {
    const forward = request(
      { host: target.hostname, port: target.port, method: req.method, path: req.url, headers: req.headers },
      (answer) => relay(req, answer, res),
    );
    forward.on("error", () => {
      if (!res.headersSent) {
        res.writeHead(502, { "content-type": "text/plain" });
        res.end("encode-proxy: the upstream did not answer");
      } else {
        res.destroy();
      }
    });
    res.on("close", () => {
      if (!res.writableFinished) forward.destroy();
    });
    req.pipe(forward);
  });
}

function relay(req, answer, res) {
  const status = answer.statusCode ?? 502;
  const headers = { ...answer.headers };
  for (const name of HOP_BY_HOP) delete headers[name];
  const length = headers["content-length"] === undefined ? null : Number(headers["content-length"]);
  const streamed = length === null;
  const chosen = chooseEncoding(req.headers["accept-encoding"]);
  const eligible =
    chosen !== null &&
    req.method !== "HEAD" &&
    status >= 200 &&
    status !== 204 &&
    status !== 304 &&
    !headers["content-encoding"] &&
    !/(^|,)\s*no-transform\s*(,|$)/i.test(String(headers["cache-control"] ?? "")) &&
    encodesType(headers["content-type"]);

  let decided = false;
  let enc = null;
  const decide = (firstWrite) => {
    decided = true;
    if (eligible && (firstWrite > MIN_LENGTH || (length ?? 0) > MIN_LENGTH)) {
      delete headers["content-length"];
      delete headers["accept-ranges"];
      headers["content-encoding"] = chosen;
      headers["vary"] = headers["vary"] ? `${headers["vary"]}, Accept-Encoding` : "Accept-Encoding";
      const etag = headers["etag"];
      if (typeof etag === "string" && !etag.startsWith("W/") && etag.endsWith('"')) {
        headers["etag"] = `${etag.slice(0, -1)}-${chosen}"`;
      }
      enc = encoder(chosen);
      enc.pipe(res);
    }
    // Harness-only (Caddy sends nothing like it): the first write Caddy would have decided on, so budget.spec.ts can
    // judge an unencoded streamed body by the rule Caddy applies (budget.ts, unencodedByCaddy), not by its total.
    if (streamed) headers[FIRST_WRITE_HEADER] = String(firstWrite);
    res.writeHead(status, headers);
  };
  const flushKind = () => (enc && headers["content-encoding"] === "zstd" ? constants.ZSTD_e_flush : constants.Z_SYNC_FLUSH);
  const wait = (stream) => new Promise((ok) => stream.once("drain", ok));

  (async () => {
    for await (const chunk of answer) {
      if (chunk.length === 0) continue;
      if (!decided) decide(chunk.length);
      if (enc) {
        if (!enc.write(chunk)) await wait(enc);
        if (streamed) await new Promise((ok) => enc.flush(flushKind(), ok));
      } else if (!res.write(chunk)) {
        await wait(res);
      }
    }
    if (!decided) decide(0);
    if (enc) enc.end();
    else res.end();
  })().catch(() => res.destroy());
}

// Started as a script (playwright.config.ts's webServer), never when a test imports the helpers (no `import.meta`:
// Playwright's workers may load this file as CommonJS).
if (basename(process.argv[1] ?? "") === "encode-proxy.mjs") {
  const port = Number(process.env.ENCODE_PORT ?? 3217);
  const upstream = process.env.ENCODE_UPSTREAM;
  if (!upstream) throw new Error("encode-proxy: ENCODE_UPSTREAM (the next start origin) is required");
  startEncodeProxy({ upstream }).listen(port, process.env.ENCODE_HOST ?? "127.0.0.1");
}
