import { expect, test, type Page } from "@playwright/test";

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

test.describe("CSP on Next pages", () => {
  test("the probe renders with zero violations, every script nonced and no inline style", async ({ page }) => {
    await collectViolations(page);
    const response = await page.goto("/app/probe");
    expect(response?.status()).toBe(200);
    await page.waitForLoadState("networkidle");

    const header = response?.headers()["content-security-policy"];
    expect(header, "CSP header on /app/probe").toBeTruthy();
    const nonce = nonceOf(header);
    expect(nonce, "nonce in the CSP header").toBeTruthy();

    expect(await violations(page)).toEqual([]);

    // The nonce IDL property, not the attribute: browsers blank the attribute once the CSP has applied it.
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

  test("/app/healthz carries the policy and reports no violation", async ({ page }) => {
    await collectViolations(page);
    const response = await page.goto("/app/healthz");
    expect(response?.status()).toBe(200);
    expect(response?.headers()["content-security-policy"], "CSP header on /app/healthz").toBeTruthy();
    expect(response?.headers()["cache-control"]).toBe("no-store");
    expect(await response?.json()).toMatchObject({ service: "medioo-web" });
    expect(await violations(page)).toEqual([]);
  });

  test("the nonce is fresh on every request", async ({ request }) => {
    const a = nonceOf((await request.get("/app/probe")).headers()["content-security-policy"]);
    const b = nonceOf((await request.get("/app/probe")).headers()["content-security-policy"]);
    expect(a).toBeTruthy();
    expect(b).toBeTruthy();
    expect(a).not.toBe(b);
  });

  test("the security headers are set on pages and on static assets", async ({ request }) => {
    for (const path of ["/app/probe", "/app/healthz", "/fonts/noto-sans-latin.woff2"]) {
      const h = (await request.get(path)).headers();
      expect(h["x-frame-options"], `${path} x-frame-options`).toBe("DENY");
      expect(h["x-content-type-options"], `${path} x-content-type-options`).toBe("nosniff");
      expect(h["referrer-policy"], `${path} referrer-policy`).toBe("same-origin");
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
