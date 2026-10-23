import { randomBytes } from "node:crypto";
import { createServer, request, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { createGunzip, createZstdDecompress, gzipSync } from "node:zlib";
import { afterEach, describe, expect, it } from "vitest";
import { FIRST_WRITE_HEADER } from "./budget";
import { chooseEncoding, encodesType, startEncodeProxy } from "./encode-proxy.mjs";

/*
 * The e2e harness's measuring proxy (PO ruling P3, 2026-10-23): Next no longer compresses (compress: false), so in
 * production Caddy's `encode zstd gzip` compresses medioo-web's responses, and the suite's `next start` run measures
 * through this proxy, which encodes the way that Caddy does. Every rule below is Caddy's (v2.11 encode handler,
 * defaults): the encoding the browser is answered in, the first-write 512 B threshold, the content types, a body the
 * upstream already encoded left alone, and a streamed response flushed write by write. A proxy kinder than Caddy would
 * make the budget flattering; one that buffered would hide a streaming defect.
 */

type Upstream = (req: IncomingMessage, res: ServerResponse) => void;
const servers: Server[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
});

async function listen(server: Server): Promise<number> {
  servers.push(server);
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  return (server.address() as AddressInfo).port;
}

/** An upstream answering with `handler`, and the proxy in front of it; returns the proxy's port. */
async function behindProxy(handler: Upstream): Promise<number> {
  const upstream = await listen(createServer(handler));
  return listen(startEncodeProxy({ upstream: `http://127.0.0.1:${upstream}` }));
}

type Got = { status: number; headers: IncomingMessage["headers"]; wire: Buffer; decoded: Buffer };

/** One request through the proxy: the bytes as they crossed the wire, and as the browser decodes them. */
function get(port: number, path: string, headers: Record<string, string> = {}, method = "GET", body?: string): Promise<Got> {
  return new Promise((resolve, reject) => {
    const req = request({ host: "127.0.0.1", port, path, method, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => chunks.push(c));
      res.on("end", async () => {
        const wire = Buffer.concat(chunks);
        const enc = res.headers["content-encoding"];
        const decoder = enc === "zstd" ? createZstdDecompress() : enc === "gzip" ? createGunzip() : null;
        const decoded = decoder ? await new Promise<Buffer>((ok, bad) => {
          const out: Buffer[] = [];
          decoder.on("data", (c: Buffer) => out.push(c)).on("end", () => ok(Buffer.concat(out))).on("error", bad);
          decoder.end(wire);
        }) : wire;
        resolve({ status: res.statusCode ?? 0, headers: res.headers, wire, decoded });
      });
      res.on("error", reject);
    });
    req.on("error", reject);
    req.end(body);
  });
}

const CHROME = "gzip, deflate, br, zstd";
// An HTML page that compresses as Next's do: repetitive markup.
const page = (n: number) => Buffer.from("<div class=\"row\"><span>Haemoglobin</span><b>13.2 g/dL</b></div>\n".repeat(n));

describe("the encoding Caddy answers in (encode zstd gzip)", () => {
  it("prefers zstd, its first-listed encoding, whenever the browser offers it, as Chrome on a phone does", () => {
    expect(chooseEncoding(CHROME)).toBe("zstd");
    expect(chooseEncoding("zstd, gzip")).toBe("zstd");
    expect(chooseEncoding("gzip, zstd")).toBe("zstd");
  });

  it("falls back to gzip, and to nothing when neither is offered or both are refused", () => {
    expect(chooseEncoding("gzip, deflate, br")).toBe("gzip");
    expect(chooseEncoding("br")).toBeNull();
    expect(chooseEncoding(undefined)).toBeNull();
    expect(chooseEncoding("zstd;q=0, gzip;q=0")).toBeNull();
  });

  it("honours the browser's weights before its own order", () => {
    expect(chooseEncoding("zstd;q=0.5, gzip")).toBe("gzip");
    expect(chooseEncoding("zstd;q=0, gzip")).toBe("gzip");
  });

  it("encodes Caddy's default types and nothing else: the woff2 subsets and the webp images go unencoded", () => {
    for (const type of ["text/html; charset=utf-8", "text/x-component", "text/css", "application/javascript; charset=UTF-8", "application/json", "image/svg+xml"]) {
      expect(encodesType(type), type).toBe(true);
    }
    for (const type of ["font/woff2", "image/webp", "image/png", "image/jpeg", "application/octet-stream", undefined]) {
      expect(encodesType(type), String(type)).toBe(false);
    }
  });
});

describe("startEncodeProxy", () => {
  it("aStreamedPageIsEncodedInZstdForChromeAndDecodesToTheSameBytes", async () => {
    const body = page(400);
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      res.write(body.subarray(0, 9000));
      res.end(body.subarray(9000));
    });
    const got = await get(port, "/app/probe", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBe("zstd");
    expect(got.headers["vary"]).toMatch(/Accept-Encoding/);
    expect(got.headers["content-length"]).toBeUndefined();
    expect(got.decoded.equals(body)).toBe(true);
    expect(got.wire.length, "the wire carries fewer bytes than the page").toBeLessThan(body.length / 4);
  });

  it("aBrowserWithoutZstdGetsGzip", async () => {
    const body = page(200);
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html", "content-length": String(body.length) });
      res.end(body);
    });
    const got = await get(port, "/", { "accept-encoding": "gzip, deflate" });
    expect(got.headers["content-encoding"]).toBe("gzip");
    expect(got.headers["content-length"], "a known length is dropped once the body is re-encoded").toBeUndefined();
    expect(got.decoded.equals(body)).toBe(true);
  });

  it("eachStreamedWriteReachesTheBrowserBeforeTheUpstreamSendsTheNext", async () => {
    // The upstream sends its second part only once the browser has DECODED the first: a proxy that buffered the response
    // (or an encoder that held its output until the end) never lets the first part through, and the test times out.
    const first = page(40);
    const second = page(60);
    let release!: () => void;
    const released = new Promise<void>((ok) => (release = ok));
    const port = await behindProxy(async (_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.write(first);
      await released;
      res.end(second);
    });
    const decoded = await new Promise<Buffer>((resolve, reject) => {
      const timer = setTimeout(() => { release(); reject(new Error("the first write never reached the browser before the second was sent")); }, 3000);
      request({ host: "127.0.0.1", port, path: "/", headers: { "accept-encoding": CHROME } }, (res) => {
        expect(res.headers["content-encoding"]).toBe("zstd");
        const out: Buffer[] = [];
        const decoder = createZstdDecompress();
        decoder.on("data", (c: Buffer) => {
          out.push(c);
          if (Buffer.concat(out).length >= first.length) {
            clearTimeout(timer);
            release();
          }
        });
        decoder.on("end", () => resolve(Buffer.concat(out)));
        res.pipe(decoder);
      })
        .on("error", reject)
        .end();
    });
    expect(decoded.equals(Buffer.concat([first, second]))).toBe(true);
  });

  it("aFirstWriteOf512BytesOrLessIsSentUnencodedAsCaddyDoes", async () => {
    // Caddy decides on the first write: over 512 B (or a Content-Length over 512) is encoded, anything else is not,
    // whatever follows. Encoding it here would measure fewer bytes than a phone gets.
    const rest = page(100);
    const port = await behindProxy(async (_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.write("x".repeat(512));
      await new Promise((ok) => setTimeout(ok, 50));
      res.end(rest);
    });
    const got = await get(port, "/", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.wire.length).toBe(512 + rest.length);
  });

  it("aFirstWriteOf513BytesIsEncoded", async () => {
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("x".repeat(513));
    });
    const got = await get(port, "/", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBe("zstd");
    expect(got.decoded.toString()).toBe("x".repeat(513));
  });

  it("aShortBodyWithAKnownLengthIsNotEncoded", async () => {
    const body = JSON.stringify({ service: "medioo-web", sha: "e2e-0123456789ab", build: "e2e-build-0123456789ab" });
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "application/json", "content-length": String(body.length) });
      res.end(body);
    });
    const got = await get(port, "/app/healthz", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.headers["content-length"]).toBe(String(body.length));
    expect(got.wire.toString()).toBe(body);
  });

  it("aFontIsPassedThroughByteForByte", async () => {
    const font = randomBytes(30_000);
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "font/woff2", "content-length": String(font.length) });
      res.end(font);
    });
    const got = await get(port, "/fonts/noto-sans-latin.woff2", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.wire.equals(font)).toBe(true);
  });

  it("aBodyTheUpstreamEncodedItselfIsNeverEncodedTwice", async () => {
    const body = gzipSync(page(200));
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html", "content-encoding": "gzip", "content-length": String(body.length) });
      res.end(body);
    });
    const got = await get(port, "/", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBe("gzip");
    expect(got.wire.equals(body)).toBe(true);
  });

  it("noTransformIsRespected", async () => {
    const body = page(100);
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html", "cache-control": "no-store, no-transform" });
      res.end(body);
    });
    const got = await get(port, "/", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.wire.equals(body)).toBe(true);
  });

  it("aBrowserThatAsksForNoEncodingGetsNone", async () => {
    const body = page(100);
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.end(body);
    });
    const got = await get(port, "/");
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.wire.equals(body)).toBe(true);
  });

  it("theProxyDropsNoBytes: a large incompressible streamed body arrives whole", async () => {
    // A property of the proxy, not of the budget: every byte the upstream wrote reaches the browser, however large the
    // body and however many writes it came in. (That the budget still fails a page genuinely over the route ceiling is
    // shown by budget.spec.ts itself, replayed red through this proxy; see the P3 PR.)
    const noise = randomBytes(300_000);
    const port = await behindProxy(async (_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      for (let at = 0; at < noise.length; at += 64 * 1024) res.write(noise.subarray(at, at + 64 * 1024));
      res.end();
    });
    const got = await get(port, "/noise", { "accept-encoding": CHROME });
    expect(got.headers["content-encoding"]).toBe("zstd");
    expect(got.decoded.equals(noise)).toBe(true);
    expect(got.wire.length, "incompressible bytes do not shrink").toBeGreaterThan(noise.length);
  });

  it("aStreamedAnswerCarriesItsFirstWriteForTheBudget, encoded or not, and a known length carries none", async () => {
    // budget.spec.ts judges an unencoded streamed body by its first write, as Caddy decides (budget.ts, unencodedByCaddy).
    const rest = page(100);
    const small = await behindProxy(async (_req, res) => {
      res.writeHead(200, { "content-type": "text/x-component" });
      res.write("x".repeat(300));
      await new Promise((ok) => setTimeout(ok, 20));
      res.end(rest);
    });
    const smallGot = await get(small, "/app/home?_rsc=1", { "accept-encoding": CHROME });
    expect(smallGot.headers["content-encoding"]).toBeUndefined();
    expect(smallGot.headers[FIRST_WRITE_HEADER]).toBe("300");

    const large = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.end(rest);
    });
    const largeGot = await get(large, "/", { "accept-encoding": CHROME });
    expect(largeGot.headers["content-encoding"]).toBe("zstd");
    expect(largeGot.headers[FIRST_WRITE_HEADER]).toBe(String(rest.length));

    const known = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "application/javascript", "content-length": String(rest.length) });
      res.end(rest);
    });
    expect((await get(known, "/a.js", { "accept-encoding": CHROME })).headers[FIRST_WRITE_HEADER]).toBeUndefined();
  });

  it("forwardsTheRequestAsSentAndTheAnswerAsGiven", async () => {
    let seen: { method?: string; url?: string; host?: string; cookie?: string; body?: string } = {};
    const port = await behindProxy((req, res) => {
      const parts: Buffer[] = [];
      req.on("data", (c: Buffer) => parts.push(c));
      req.on("end", () => {
        seen = { method: req.method, url: req.url, host: req.headers.host, cookie: req.headers.cookie, body: Buffer.concat(parts).toString() };
        res.writeHead(307, { location: "/app/?next=%2Fapp%2Fx", "set-cookie": ["a=1; Path=/", "b=2; Path=/"], "x-one": "1" });
        res.end();
      });
    });
    const got = await get(port, "/app/x?y=1", { host: "127.0.0.1:3217", cookie: "e2e_scenario=signed-out", "accept-encoding": CHROME }, "POST", "k=v");
    // The Host the browser sent reaches Next unchanged (Caddy's reverse_proxy keeps it), so redirects and origins match.
    expect(seen).toEqual({ method: "POST", url: "/app/x?y=1", host: "127.0.0.1:3217", cookie: "e2e_scenario=signed-out", body: "k=v" });
    expect(got.status).toBe(307);
    expect(got.headers["location"]).toBe("/app/?next=%2Fapp%2Fx");
    expect(got.headers["set-cookie"]).toEqual(["a=1; Path=/", "b=2; Path=/"]);
    expect(got.headers["x-one"]).toBe("1");
    expect(got.headers["content-encoding"]).toBeUndefined();
  });

  it("aHeadRequestCarriesNoBodyAndNoEncoding", async () => {
    const port = await behindProxy((_req, res) => {
      res.writeHead(200, { "content-type": "text/html", "content-length": "5000" });
      res.end();
    });
    const got = await get(port, "/", { "accept-encoding": CHROME }, "HEAD");
    expect(got.status).toBe(200);
    expect(got.headers["content-encoding"]).toBeUndefined();
    expect(got.wire.length).toBe(0);
  });
});
