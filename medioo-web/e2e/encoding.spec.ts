import { expect, test } from "@playwright/test";

/*
 * Who compresses (PO ruling P3, 2026-10-23). Next's own streaming gzip compressed the RSC-heavy document in tiny
 * flushes: the 58-value /all page's document was 64 KB on the wire where one compression stream gives under 18 KB, and
 * the page measured over the DR-42 route ceiling for it. The fix is at the cause: Next sends every body unencoded
 * (next.config.ts: compress: false) and Caddy's `encode zstd gzip` (docs/infra/Caddyfile.medioo) compresses it. This
 * asks Next itself, behind no proxy, with everything a phone's Chrome offers; budget.spec.ts asks the other side, that
 * what a phone measures did cross the wire encoded.
 *
 * Only in the suite's own run: E2E_NEXT_URL is `next start` behind the measuring proxy (e2e/support/encode-proxy.mjs).
 * Against an external server (the Caddy run) there is no direct Next origin to ask.
 */
const NEXT_URL = process.env.E2E_NEXT_URL;
// What Chrome on a phone sends (and Next's compression knows gzip and deflate, so it would answer one of them).
const PHONE_ACCEPT_ENCODING = "gzip, deflate, br, zstd";

test("nextItselfSendsEveryBodyUnencodedSoCaddyCompressesItInOneStream", async ({ playwright }) => {
  test.skip(!NEXT_URL, "no direct Next origin: the run is against an external server");
  const next = await playwright.request.newContext({ baseURL: NEXT_URL });
  try {
    const headers = { "accept-encoding": PHONE_ACCEPT_ENCODING };
    const doc = await next.get("/app/probe", { headers });
    expect(doc.status(), "/app/probe answered").toBe(200);
    const html = await doc.text();
    expect(html.length, "the probe's document is long enough that any compressor would take it").toBeGreaterThan(2048);
    expect(doc.headers()["content-encoding"], "/app/probe: Next encoded its own document").toBeUndefined();

    // The page's own scripts and stylesheet, every one, as the document names them.
    const assets = [...new Set([...html.matchAll(/(?:src|href)="(\/_next\/static\/[^"?]+\.(?:js|css))"/g)].map((m) => m[1]!))];
    expect(assets.filter((a) => a.endsWith(".js")).length, "the probe names its scripts").toBeGreaterThan(0);
    expect(assets.filter((a) => a.endsWith(".css")).length, "the probe names its stylesheet").toBeGreaterThan(0);
    for (const asset of assets) {
      const r = await next.get(asset, { headers });
      expect(r.status(), `${asset} answered`).toBe(200);
      expect(r.headers()["content-encoding"], `${asset}: Next encoded its own asset`).toBeUndefined();
    }
  } finally {
    await next.dispose();
  }
});
