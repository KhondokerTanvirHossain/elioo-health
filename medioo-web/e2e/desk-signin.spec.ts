import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS } from "./support/desk-checks";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, overflowReport } from "./support/screen-checks";

/*
 * The sign-in pages on a desktop browser (plan desktop-parity Phase 2, row I1; desktop.html `signinView()`): from 900 px
 * the welcome is desktop.html's hero (the headline, the promise, a sample answer, the trust line), beside today's form;
 * below 900 px today's welcome, unchanged. Both sign-in pages (the number, `/app`; the code, `/app/verify`), at 1440,
 * 1280, 1024 and 900 px, light and dark: axe, CSP, no element wider than its box. Against the production build and stub
 * Java, nobody signed in.
 */

const PAGES: Array<[string, string, string]> = [
  ["the number page", "signed-out", "/app/"],
  ["the code page", "state-pending-sent", "/app/verify"],
];

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

const hero = (page: Page) => page.locator(".sign-in__hero");

test.describe("the sign-in pages from 900 px", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });
      for (const colorScheme of SCHEMES) {
        for (const [what, scenario, path] of PAGES) {
          test(`axeCspAndNoOverflow: ${what} (${colorScheme}, ${name})`, async ({ page }) => {
            await page.emulateMedia({ colorScheme });
            await open(page, scenario, path);
            await expect(hero(page), "desktop.html's welcome").toBeVisible();
            await expect(page.locator(".sign-in__welcome"), "the phone's welcome").toBeHidden();
            await expectSchemeIsPainted(page, colorScheme);
            const report = await axeReport(page);
            expect(report.violations).toEqual([]);
            expect(await cspViolations(page)).toEqual([]);
            const over = await overflowReport(page, "body");
            expect(over.over).toEqual([]);
            expect(over.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(over.page[1] + 1);
          });
        }
      }
    });
  }

  test("theHeroSaysTheHeadlineThePromiseAndThatMediooIsNotADoctor", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "signed-out", "/app/");
    await expect(hero(page).getByRole("heading", { level: 2 })).toHaveText(t("bn", "desk.signin.h1"));
    await expect(hero(page)).toContainText(t("bn", "desk.signin.lede"));
    // On the first screen, in words a screen reader reads too: not a doctor, and what happens to the family's papers.
    const trust = hero(page).locator(".sign-in__trust");
    await expect(trust).toBeVisible();
    // On the first screen of a 1440×900 window, not below the fold.
    expect((await trust.boundingBox())!.y + (await trust.boundingBox())!.height, "the trust line within the window").toBeLessThanOrEqual(900);
    await expect(trust).toContainText(t("bn", "desk.signin.trust.doctor"));
    await expect(trust).toContainText(t("bn", "landing.promise.3"));
    // The sample is decoration, hidden from a screen reader, and its level is the app's own tag.
    await expect(hero(page).locator(".sign-in__demo")).toHaveAttribute("aria-hidden", "true");
    await expect(hero(page).locator(".sign-in__demo .urgency-tag")).toHaveCount(1);
    // The form beside it is today's: the number field and its one primary press.
    await expect(page.locator(".sign-in__side input[name=whatsapp_number]")).toBeVisible();
  });
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("thePhonesWelcomeIsUnchanged", async ({ page }) => {
    await open(page, "signed-out", "/app/");
    await expect(hero(page)).toBeHidden();
    await expect(page.locator(".sign-in__welcome")).toBeVisible();
    await expect(page.locator(".sign-in__welcome")).toContainText(t("bn", "chat.intro"));
  });
});
