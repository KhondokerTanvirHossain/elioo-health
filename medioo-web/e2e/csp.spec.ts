import { expect, test, type Page } from "@playwright/test";
import { buildCsp } from "../src/security/csp";

/** Records every CSP violation the page reports, from the first byte of the document on. */
async function collectViolations(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __csp: string[] };
    w.__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => {
      w.__csp.push(`${e.violatedDirective} blocked ${e.blockedURI || "inline"}`);
    });
  });
}

const violations = (page: Page) => page.evaluate(() => (window as unknown as { __csp: string[] }).__csp);

function nonceOf(csp: string | undefined): string | undefined {
  return csp?.match(/'nonce-([^']+)'/)?.[1];
}

/**
 * The policy actually sent is the policy: the header must be exactly buildCsp() for the nonce it carries. A presence
 * check let the middleware send any string (`script-src 'self' https: 'unsafe-eval'` stayed green); this cannot.
 */
function expectExactCsp(header: string | undefined, where: string) {
  const nonce = nonceOf(header);
  expect(nonce, `nonce in the CSP header on ${where}`).toBeTruthy();
  expect(header, `CSP header on ${where}`).toBe(buildCsp(nonce!));
}

/** The values Next sends on every response it serves (Global Constraints, DR-40 A1: the same value Caddy and Java send). */
const SECURITY_HEADERS = {
  "x-frame-options": "DENY",
  "x-content-type-options": "nosniff",
  "referrer-policy": "strict-origin-when-cross-origin",
} as const;

function expectSecurityHeaders(headers: Record<string, string>, where: string) {
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) expect(headers[name], `${where} ${name}`).toBe(value);
}

test.describe("CSP on Next pages", () => {
  test("the probe renders with zero violations, every script nonced and no inline style", async ({ page }) => {
    await collectViolations(page);
    const response = await page.goto("/app/probe");
    expect(response?.status()).toBe(200);
    await page.waitForLoadState("networkidle");

    const header = response?.headers()["content-security-policy"];
    expectExactCsp(header, "/app/probe");
    const nonce = nonceOf(header);

    expect(await violations(page)).toEqual([]);

    // The nonce IDL property, not the attribute: browsers blank the attribute once the CSP has applied it. This is
    // also the guard on how Next learns the nonce (see middleware.ts): if it stopped stamping it, this fails.
    const scripts = await page.evaluate(() =>
      Array.from(document.querySelectorAll("script")).map((s) => ({ src: s.src || "inline", nonce: s.nonce })),
    );
    expect(scripts.length, "the probe ships Next's scripts").toBeGreaterThan(0);
    for (const s of scripts) expect(s.nonce, `nonce on script ${s.src}`).toBe(nonce);

    // No style attribute in the markup we serve: that is what style-src 'self' blocks.
    const html = (await response?.text()) ?? "";
    expect(html.length).toBeGreaterThan(0);
    expect(html.match(/<[a-z][^>]*\sstyle=/gi) ?? []).toEqual([]);

    // Nor in the live DOM, with one exact exception: Next's <next-route-announcer>, whose position its runtime sets
    // through CSSOM (element.style), which CSP permits — the zero-violation assertion above is the proof it is not
    // blocked. Anything else with a style attribute fails, including another attribute on the announcer.
    const styled = await page.evaluate(() =>
      Array.from(document.querySelectorAll("[style]")).map((el) => el.outerHTML.slice(0, 120)),
    );
    expect(styled.filter((s) => s !== '<next-route-announcer style="position: absolute;"></next-route-announcer>')).toEqual([]);
  });

  test("/app/healthz carries the policy and names the image it runs", async ({ page }) => {
    // The sha the server was started with (playwright.config.ts sets GIT_SHA from it). W1b reads this route to
    // confirm the right image is running, so a constant or a missing sha must fail here.
    const sha = process.env.E2E_GIT_SHA;
    const build = process.env.E2E_BUILD_SHA;
    expect(sha, "E2E_GIT_SHA: the GIT_SHA the server under test was started with").toBeTruthy();
    expect(build, "E2E_BUILD_SHA: the BUILD_SHA the server under test was started with").toBeTruthy();

    await collectViolations(page);
    const response = await page.goto("/app/healthz");
    expect(response?.status()).toBe(200);
    expectExactCsp(response?.headers()["content-security-policy"], "/app/healthz");
    expect(response?.headers()["cache-control"]).toBe("no-store");
    expect(await response?.json()).toEqual({ service: "medioo-web", sha, build });
    expect(await violations(page)).toEqual([]);
  });

  test("<html lang> is bn whatever Accept-Language says", async ({ request }) => {
    for (const lang of ["en", "en-US,en;q=0.9", "bn"]) {
      const html = await (await request.get("/app/probe", { headers: { "accept-language": lang } })).text();
      expect(html.match(/<html[^>]*\slang="([^"]*)"/)?.[1], `lang with Accept-Language: ${lang}`).toBe("bn");
    }
  });

  test("the nonce is fresh on every request", async ({ request }) => {
    const a = nonceOf((await request.get("/app/probe")).headers()["content-security-policy"]);
    const b = nonceOf((await request.get("/app/probe")).headers()["content-security-policy"]);
    expect(a).toBeTruthy();
    expect(b).toBeTruthy();
    expect(a).not.toBe(b);
  });

  // Derived, not listed: every same-origin response a cold probe load makes (HTML, every script, the stylesheet, both
  // fonts), plus the health route. The set is guarded against vacuity so a load that fetched nothing cannot pass.
  test("the security headers are set on every response a page load makes", async ({ page, baseURL }) => {
    const origin = new URL(baseURL!).origin;
    const seen = new Map<string, Record<string, string>>();
    page.on("response", (r) => {
      if (new URL(r.url()).origin === origin) seen.set(r.url().replace(origin, ""), r.headers());
    });
    await page.goto("/app/probe");
    await page.waitForLoadState("networkidle");
    await page.evaluate(() => document.fonts.ready.then(() => undefined));
    await page.waitForLoadState("networkidle");
    const health = await page.request.get("/app/healthz");
    seen.set("/app/healthz", health.headers());

    const paths = [...seen.keys()];
    expect(paths, "the HTML").toContain("/app/probe");
    expect(paths.filter((p) => /^\/_next\/static\/.+\.js$/.test(p)).length, "a /_next/static script").toBeGreaterThan(0);
    expect(paths.filter((p) => /^\/_next\/static\/.+\.css$/.test(p)).length, "the stylesheet").toBeGreaterThan(0);
    expect(paths, "both fonts").toEqual(
      expect.arrayContaining(["/fonts/noto-sans-bengali.woff2", "/fonts/noto-sans-latin.woff2"]),
    );

    for (const [path, headers] of seen) expectSecurityHeaders(headers, path);
  });

  // A path nothing serves must be an honest 404 under the policy, never a 500 error page with un-nonced scripts and no
  // CSP. /fonts/* and look-alikes of /_next/static used to skip the middleware, reach the root layout without a nonce
  // and 500. /_next/image is the image optimizer: the app bans next/image, so it must not answer at all.
  for (const path of [
    "/nope",
    "/fonts/missing.woff2",
    "/fonts/",
    "/_next/staticx",
    "/_next/image?url=%2Ffonts%2Fnoto-sans-latin.woff2&w=64&q=75",
  ]) {
    test(`${path} is a 404 under the exact policy`, async ({ request }) => {
      const response = await request.get(path);
      expect(response.status(), `${path} status`).toBe(404);
      const headers = response.headers();
      expectExactCsp(headers["content-security-policy"], path);
      expectSecurityHeaders(headers, path);
    });
  }

  test("a real font is served with the security headers", async ({ request }) => {
    for (const path of ["/fonts/noto-sans-bengali.woff2", "/fonts/noto-sans-latin.woff2"]) {
      const response = await request.get(path);
      expect(response.status(), `${path} status`).toBe(200);
      expect(response.headers()["content-type"], `${path} content-type`).toBe("font/woff2");
      expectSecurityHeaders(response.headers(), path);
    }
  });

  // The two "zero violations" assertions above are only worth something if the collector can see a violation at all.
  // This one makes the page break its own policy and requires the hook to report it.
  test("the violation hook reports a violation when one happens", async ({ page }) => {
    await collectViolations(page);
    await page.goto("/app/probe");
    await page.evaluate(() => document.body.setAttribute("style", "outline: 1px solid"));
    await expect.poll(() => violations(page)).toContainEqual(expect.stringContaining("style-src"));
  });
});
