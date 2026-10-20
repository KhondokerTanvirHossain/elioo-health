import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

// The legibility floor (axe: zero violations, both themes) and the no-JS reading rule (plan A4) start with the probe,
// so every later routed page inherits a suite that already runs them.
for (const colorScheme of ["light", "dark"] as const) {
  test(`axe reports no violation on the probe (${colorScheme})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme });
    await page.goto("/app/probe");
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
