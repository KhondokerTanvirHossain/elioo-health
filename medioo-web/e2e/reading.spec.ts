import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

// The legibility floor (axe: zero violations, both themes) and the no-JS reading rule (plan A4) start with the probe,
// so every later routed page inherits a suite that already runs them.
for (const colorScheme of ["light", "dark"] as const) {
  test(`axe reports no violation on the probe (${colorScheme})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme });
    await page.goto("/app/probe");

    // The theme under test is the one painted. Without this, a page whose dark tokens are missing paints the light
    // theme under a "dark" label and axe passes it. Nothing is hard-coded: the body must paint the page's own --c-bg
    // as the active scheme resolves it, and the other scheme must paint a different background.
    const paint = () =>
      page.evaluate(() => {
        const swatch = document.createElement("div");
        swatch.style.setProperty("background-color", "var(--c-bg)"); // CSSOM, which style-src 'self' permits
        document.body.append(swatch);
        const token = getComputedStyle(swatch).backgroundColor;
        swatch.remove();
        return {
          dark: matchMedia("(prefers-color-scheme: dark)").matches,
          token,
          body: getComputedStyle(document.body).backgroundColor,
        };
      });
    const active = await paint();
    expect(active.dark, "prefers-color-scheme is emulated").toBe(colorScheme === "dark");
    expect(active.body, "the body paints the active scheme's --c-bg").toBe(active.token);
    const other = colorScheme === "dark" ? "light" : "dark";
    await page.emulateMedia({ colorScheme: other });
    expect((await paint()).body, `the ${other} theme paints a different background`).not.toBe(active.body);
    await page.emulateMedia({ colorScheme });
    expect((await paint()).body, `back on ${colorScheme}`).toBe(active.body);

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.length} node(s)`)).toEqual([]);
    // An analysis that checked nothing would also report nothing.
    expect(results.passes.length).toBeGreaterThan(0);
  });
}

test.describe("with JavaScript off", () => {
  test.use({ javaScriptEnabled: false });

  test("the probe is still readable", async ({ page }) => {
    await page.goto("/app/probe");
    await expect(page.locator("main h1")).not.toBeEmpty();
    await expect(page.locator("main p")).not.toBeEmpty();
    await expect(page.locator("nav a")).toHaveCount(3);
  });
});
