// The two ends of scripts/caddy-streaming-check.sh (PO ruling P3): does a streamed medioo-web response still stream
// through Caddy's `encode zstd gzip`? Plain ESM, no dependencies; Node 22.15 or later (zstd).
//
//   node scripts/caddy-streaming.mjs upstream <port>                     the stand-in for medioo-web
//   node scripts/caddy-streaming.mjs client <url> <accept-encoding> <expected content-encoding>
//
// The upstream answers every request as a streamed page does (no Content-Length): FIRST_PART, well over Caddy's 512 B
// so the encoder engages, then nothing for HOLD_MS (a slow Java read behind a Suspense boundary), then the rest. The
// client asks through Caddy, decodes as a browser does and reports when the first part was decoded. A Caddy that held
// the response until the end (a buffered reverse_proxy, an encoder that does not flush) decodes nothing until the
// hold is over, and the client fails, naming the path and the time. A run whose upstream did not hold proves nothing,
// so that fails too. Prints one line: PASS or FAIL, the path, the encoding and the times (no body text).
import { createServer, request } from "node:http";
import { createGunzip, createZstdDecompress } from "node:zlib";

export const HOLD_MS = 1500;
// Well before the hold ends: a first part that took half the hold to arrive was held back.
export const FIRST_PART_BY_MS = HOLD_MS / 2;
const row = (i) => `<div class="row"><span>Haemoglobin ${i}</span><b>13.${i % 10} g/dL</b></div>\n`;
export const FIRST_PART = Buffer.from(Array.from({ length: 60 }, (_, i) => row(i)).join(""));
export const REST = Buffer.from(Array.from({ length: 120 }, (_, i) => row(i + 60)).join(""));

function upstream(port) {
  createServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    res.write(FIRST_PART);
    setTimeout(() => res.end(REST), HOLD_MS);
  }).listen(port, process.env.STREAM_HOST ?? "0.0.0.0");
}

function client(url, acceptEncoding, expected) {
  const path = new URL(url).pathname;
  const say = (verdict, detail) => {
    console.log(`${verdict} ${path} ${expected}: ${detail}`);
    process.exitCode = verdict === "PASS" ? 0 : 1;
  };
  const started = Date.now();
  const req = request(url, { headers: { "accept-encoding": acceptEncoding } }, (res) => {
    const got = res.headers["content-encoding"] ?? "identity";
    if (res.statusCode !== 200) return say("FAIL", `answered ${res.statusCode}`), res.resume();
    if (got !== expected) return say("FAIL", `content-encoding is ${got}, expected ${expected}`), res.resume();
    if (typeof createZstdDecompress !== "function") return say("FAIL", `Node ${process.version} has no zstd (22.15 or later)`);
    const decoder = got === "zstd" ? createZstdDecompress() : createGunzip();
    const parts = [];
    let length = 0;
    let firstAt = null;
    decoder.on("data", (chunk) => {
      parts.push(chunk);
      length += chunk.length;
      if (firstAt === null && length >= FIRST_PART.length) firstAt = Date.now() - started;
    });
    decoder.on("error", (e) => say("FAIL", `the body did not decode as ${got} (${e.code ?? e.message})`));
    decoder.on("end", () => {
      const totalMs = Date.now() - started;
      const times = `first part decoded at ${firstAt} ms, whole body at ${totalMs} ms, upstream hold ${HOLD_MS} ms`;
      if (!Buffer.concat(parts).equals(Buffer.concat([FIRST_PART, REST]))) return say("FAIL", `the body is not what the upstream sent (${times})`);
      if (totalMs < HOLD_MS) return say("FAIL", `the upstream did not hold, so this run shows nothing (${times})`);
      if (firstAt >= FIRST_PART_BY_MS) return say("FAIL", `the first part was held back until the end, not streamed (${times})`);
      say("PASS", times);
    });
    res.pipe(decoder);
  });
  req.setTimeout(HOLD_MS * 4, () => req.destroy(new Error("timed out")));
  req.on("error", (e) => say("FAIL", `no answer (${e.code ?? e.message})`));
  req.end();
}

const [mode, ...args] = process.argv.slice(2);
if (mode === "upstream" && args.length === 1) upstream(Number(args[0]));
else if (mode === "client" && args.length === 3) client(args[0], args[1], args[2]);
else {
  console.error("usage: caddy-streaming.mjs upstream <port> | client <url> <accept-encoding> <expected content-encoding>");
  process.exit(2);
}
