import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS, deskOverflow, showSidebar } from "./support/desk-checks";
import { PROTO_PATIENTS, longNameOf } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, rendersOwnText } from "./support/screen-checks";

/*
 * The family and account page on the desktop layout (plan desktop-parity Phase 2, row F1; desktop.html
 * `settingsView()`), against the production build and stub Java, on preview pages (/app/preview/family, the sidebar's
 * account row). desktop.html's own family (the `proto` scenario: the mother with diabetes and high blood pressure, the
 * father with none), a share member, a family whose reminders are stopped, and the longest name, at 1440, 1280, 1024 and
 * 900 px, light and dark: axe, CSP, no element wider than its box. Then, in a real browser: choosing a person keeps the
 * page and moves the tick and the person's sections; the account row is this page. Below 900 px the same preview page is
 * today's phone family tab.
 */

const { amma: AMMA, abbu: ABBU } = PROTO_PATIENTS;
const family = (query = "") => `/app/preview/family${query}`;

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await expect(page.locator(".desktop-shell .desk-fam"), "the desktop family page is drawn").toBeVisible();
}

const row = (page: Page, id: string) => page.locator(`.desktop-shell .desk-fam [data-patient="${id}"]`);
const section = (page: Page, name: string) => page.locator(`.desktop-shell .desk-fam [data-section="${name}"]`);

test.describe("the family page on the desktop layout", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });
      for (const colorScheme of SCHEMES) {
        test.describe(colorScheme, () => {
          test.use({ colorScheme });
          const pages: Array<[string, string, string]> = [
            ["the prototype family, the mother in view", "proto", family(`?patient=${AMMA}`)],
            ["a share member", "family-share-member", family()],
            ["a family whose reminders are stopped", "family-reminders-off", family()],
            ["the longest name", "family-owner-long", family()],
          ];
          for (const [what, scenario, path] of pages) {
            test(`axeReportsNoViolation: ${what} (${name})`, async ({ page }) => {
              await open(page, scenario, path);
              await expectSchemeIsPainted(page, colorScheme);
              const report = await axeReport(page);
              expect(report.violations).toEqual([]);
              expect(report.passes, "axe checked something").toBeGreaterThan(0);
              expect(await cspViolations(page), "CSP violations").toEqual([]);
            });

            test(`noElementOverflowsItsBox: ${what} (${name})`, async ({ page }) => {
              await open(page, scenario, path);
              await showSidebar(page, viewport.width);
              if (scenario === "family-owner-long") {
                expect(await rendersOwnText(page, longNameOf(scenario), ".desk-fam__people"), "the long name is drawn").toBe(true);
              }
              const report = await deskOverflow(page);
              expect(report.measured, "elements measured").toBeGreaterThan(30);
              expect(report.over).toEqual([]);
              expect(report.truncated.filter((x) => x.where.length === 0)).toEqual([]);
              expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
            });
          }
        });
      }
    });
  }

  test("choosingAPersonKeepsThePageAndMovesTheirSections", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", family(`?patient=${AMMA}`));
    await expect(row(page, AMMA)).toHaveAttribute("aria-current", "page");
    await expect(row(page, AMMA).locator("[data-tick]")).toHaveCount(1);
    await expect(section(page, "flags").getByRole("heading", { level: 2 })).toHaveText(t("bn", "desk.fam.flags", "\u0986\u09AE\u09CD\u09AE\u09BE\u09B0"));
    // The mother's two conditions are ticked.
    await expect(section(page, "flags").locator("input[type=checkbox]:checked")).toHaveCount(2);
    await row(page, ABBU).click();
    await expect(page).toHaveURL(new RegExp(`/app/preview/family\\?patient=${ABBU}$`));
    await expect(row(page, ABBU)).toHaveAttribute("aria-current", "page");
    await expect(row(page, AMMA).locator("[data-tick]")).toHaveCount(0);
    await expect(section(page, "flags").getByRole("heading", { level: 2 })).toHaveText(t("bn", "desk.fam.flags", "\u0986\u09AC\u09CD\u09AC\u09C1\u09B0"));
    await expect(section(page, "flags").locator("input[type=checkbox]:checked")).toHaveCount(0);
  });

  test("theAccountRowIsThisPage", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", family(`?patient=${AMMA}`));
    const account = page.locator(".desktop-shell .desktop-sidebar__account");
    await expect(account).toHaveAttribute("aria-current", "page");
    await expect(account).toHaveAttribute("href", /^\/app\/preview\/family(\?|$)/);
  });
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("aPreviewFamilyPageIsTodaysPhoneTab", async ({ page }) => {
    await useScenario(page, "proto");
    const response = await page.goto(family(`?patient=${AMMA}`));
    expect(response?.status()).toBe(200);
    await expect(page.locator(".desk-fam")).toBeHidden();
    await expect(page.locator(".family").first()).toBeVisible();
  });
});
