import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS, deskOverflow, hydrated, showSidebar } from "./support/desk-checks";
import { PROTO_PAPERS, PROTO_PATIENTS, longNameOf } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, rendersOwnText } from "./support/screen-checks";

/*
 * The medicines on the desktop layout (plan desktop-parity Phase 2, row M1; desktop.html `medsView()`), against the
 * production build and stub Java, on preview pages (/app/preview/health?show=medicines: the health page's second desktop
 * screen, the sidebar's medicines item). desktop.html's own family (the `proto` scenario: the mother's prescription of
 * the 15th, two medicines), a newer prescription still being read, none yet, and the longest name in the heading, at
 * 1440, 1280, 1024 and 900 px, light and dark: axe, CSP, no element wider than its box. Then, in a real browser: the
 * sidebar's medicines item is this screen and the lab values item is not; a medicine's "see it on the paper" opens the
 * prescription on the talk-and-papers screen. Below 900 px the same preview page is today's phone health tab.
 */

const AMMA = PROTO_PATIENTS.amma;
const meds = (query = "") => `/app/preview/health?show=medicines${query}`;

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await expect(page.locator(".desktop-shell .desk-meds"), "the desktop medicines are drawn").toBeVisible();
  // Keys and soft links reach a handler only once React owns the page.
  await hydrated(page);
}

test.describe("the medicines on the desktop layout", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });
      for (const colorScheme of SCHEMES) {
        test.describe(colorScheme, () => {
          test.use({ colorScheme });
          const pages: Array<[string, string, string]> = [
            ["the mother's prescription", "proto", meds(`&patient=${AMMA}`)],
            ["a newer prescription still being read", "health-newer-unread", meds()],
            ["no prescription yet", "health-none", meds()],
            ["the longest name in the heading", "health-long", meds()],
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
              if (scenario === "health-long") {
                expect(await rendersOwnText(page, longNameOf(scenario), ".desk-meds__col"), "the long name is in the heading").toBe(true);
              }
              const report = await deskOverflow(page);
              expect(report.measured, "elements measured").toBeGreaterThan(20);
              expect(report.over).toEqual([]);
              expect(report.truncated.filter((x) => x.where.length === 0)).toEqual([]);
              expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
            });
          }
        });
      }
    });
  }

  test("theSidebarsMedicinesItemIsThisScreen", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", meds(`&patient=${AMMA}`));
    const item = (key: "desk.nav.meds" | "desk.nav.labs") => page.locator(".desktop-shell .desktop-sidebar__item", { hasText: t("bn", key) });
    await expect(item("desk.nav.meds")).toHaveAttribute("aria-current", "page");
    await expect(item("desk.nav.labs")).not.toHaveAttribute("aria-current", "page");
    // From the lab values, the medicines item leads here.
    await page.goto(`/app/preview/health?patient=${AMMA}`);
    await expect(page.locator(".desktop-shell .desk-labs")).toBeVisible();
    await item("desk.nav.meds").click();
    await expect(page).toHaveURL(/\/app\/preview\/health\?show=medicines$/);
    await expect(page.locator(".desktop-shell .desk-meds")).toBeVisible();
  });

  test("aMedicinesPaperLinkOpensThePrescriptionOnTheTalkAndPapersScreen", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", meds(`&patient=${AMMA}`));
    const cards = page.locator(".desktop-shell [data-medicine]");
    await expect(cards).toHaveCount(2);
    await cards.first().getByRole("link", { name: t("bn", "desk.meds.paper") }).click();
    await expect(page).toHaveURL(new RegExp(`/app/preview/home\\?patient=${AMMA}&paper=${PROTO_PAPERS.rx}$`));
    await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.rx);
  });
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("aPreviewMedicinesPageIsTodaysPhoneHealthTab", async ({ page }) => {
    await useScenario(page, "proto");
    const response = await page.goto(meds(`&patient=${AMMA}`));
    expect(response?.status()).toBe(200);
    await expect(page.locator(".desk-meds")).toBeHidden();
    await expect(page.locator("section[aria-labelledby=health-medicines]")).toBeVisible();
  });
});
