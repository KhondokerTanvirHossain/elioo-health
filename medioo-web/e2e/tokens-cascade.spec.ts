import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";
import { normaliseColour, resolveTokens, type TokenFile } from "../src/styles/build-tokens";

// Two themes × two layouts is a property of the cascade, not of the CSS text (PR #120 review): which block wins
// depends on selector specificity and order, so a renderer that reordered the blocks or dropped a :not() would leave
// every unit test green while desktop dark painted light --label / --bg2. This reads what the browser applies on
// <html>, for every colour token, and compares it with the resolver, in every combination of viewport (this
// project's), prefers-color-scheme and data-theme.
const src = JSON.parse(readFileSync(join(__dirname, "../src/styles/medioo.tokens.json"), "utf8")) as TokenFile;
const resolved = resolveTokens(src);
const names = Object.keys(src.color);

for (const colorScheme of ["light", "dark"] as const) {
  for (const dataTheme of [null, "light", "dark"] as const) {
    test(`every colour token is the resolver's value (${colorScheme} scheme, data-theme ${dataTheme ?? "unset"})`, async ({ page }) => {
      await page.emulateMedia({ colorScheme });
      await page.goto("/app/probe");
      const width = page.viewportSize()!.width;
      const layout = width >= 900 ? "desktop" : "phone";
      const theme = dataTheme ?? colorScheme;

      const applied = await page.evaluate(
        ({ names, dataTheme }) => {
          const root = document.documentElement;
          if (dataTheme === null) root.removeAttribute("data-theme");
          else root.setAttribute("data-theme", dataTheme);
          const style = getComputedStyle(root);
          return Object.fromEntries(names.map((n) => [n, style.getPropertyValue(`--${n}`).trim()]));
        },
        { names, dataTheme },
      );

      const expected = resolved[theme][layout];
      // The production build minifies rgba(1, 2, 3, 0.5) to rgba(1,2,3,.5); one spelling per value on both sides.
      const wrong = names
        .filter((n) => normaliseColour(applied[n] ?? "") !== normaliseColour(expected[n]!))
        .map((n) => `--${n}: ${applied[n]} (expected ${expected[n]})`);
      expect(wrong, `${theme}/${layout} at ${width}px`).toEqual([]);
      // The read saw the tokens at all, and the combination under test is the one that differs where it should.
      expect(names.length).toBeGreaterThanOrEqual(30);
      expect(applied.now).toBe(theme === "dark" ? "#d93229" : "#d12c24");
    });
  }
}
