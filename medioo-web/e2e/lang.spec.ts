import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";

// Language: bn by default; Accept-Language never changes it, only the baymax_lang cookie does (master plan, Web
// platform). The heading is compared with the generated copy for that language, read from the file Java wrote, so
// lang="en" is never asserted over Bangla text (nor lang="bn" over English).
function heading(lang: "bn" | "en"): string {
  const copy = JSON.parse(readFileSync(join(__dirname, `../src/copy/generated/copy.${lang}.json`), "utf8")) as Record<
    string,
    unknown
  >;
  const h1 = copy["landing.h1"];
  if (typeof h1 !== "string" || h1.length === 0) throw new Error(`no landing.h1 in copy.${lang}.json`);
  return h1;
}

test.describe("with an English browser", () => {
  test.use({ locale: "en-US", extraHTTPHeaders: { "accept-language": "en-US" } });

  test("Accept-Language never changes the language", async ({ page }) => {
    await page.goto("/app/probe");
    await expect(page.locator("html")).toHaveAttribute("lang", "bn");
    await expect(page.locator("main h1")).toHaveText(heading("bn"));
  });

  test("the baymax_lang cookie does", async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: "baymax_lang", value: "en", url: baseURL! }]);
    await page.goto("/app/probe");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await expect(page.locator("main h1")).toHaveText(heading("en"));
    expect(heading("en")).not.toBe(heading("bn"));
  });
});
