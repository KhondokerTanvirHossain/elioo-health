import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS, deskOverflow, showSidebar } from "./support/desk-checks";
import { PROTO_PAPERS, PROTO_PATIENTS, longNameOf } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, rendersOwnText } from "./support/screen-checks";

/*
 * The lab values on the desktop layout (plan desktop-parity Phase 2, row L1; desktop.html `labsView()`), against the
 * production build and stub Java, on preview pages (/app/preview/health, the health page, whose desktop layout this is;
 * the only requests it renders for until the flip). desktop.html's own family (the `proto` scenario: the mother's
 * creatinine due this week), a trend under the longest name, readings printed as bounds, blood pressure's pairs and
 * nothing read yet, at 1440, 1280, 1024 and 900 px, light and dark: axe, CSP, no element wider than its box. Then, in a
 * real browser: the list and the chosen value share the window; a value is a link that moves the detail; a reading's
 * paper opens on the talk-and-papers screen; the sidebar's lab values item is this screen. Below 900 px the same
 * preview page is today's phone health tab.
 */

const AMMA = PROTO_PATIENTS.amma;
const labs = (query = "") => `/app/preview/health${query}`;
type Series = { canonical_name: string; name: string; unit: string | null; points: Array<{ observation_id: string; document_id: string }> };
/** The mother's lab values as Java answered them (ProtoFamilyContractTest's fixture, the one the stub serves her). */
const ammaSeries = (JSON.parse(readFileSync(join(__dirname, "..", "fixtures", "api", "health", "proto-labs-amma.json"), "utf8")).body as { series: Series[] })
  .series;
const ammaOf = (canonical: string) => ammaSeries.find((s) => s.canonical_name === canonical)!;
const CREATININE = ammaOf("creatinine");
const POTASSIUM = ammaOf("potassium");

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await expect(page.locator(".desktop-shell .desk-labs"), "the desktop lab values are drawn").toBeVisible();
}

const detail = (page: Page) => page.locator(".desktop-shell .desk-labs__detail");
const row = (page: Page, key: string) => page.locator(`.desktop-shell .desk-labs__row[data-lab="${key}"]`);

test.describe("the lab values on the desktop layout", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });
      for (const colorScheme of SCHEMES) {
        test.describe(colorScheme, () => {
          test.use({ colorScheme });
          const pages: Array<[string, string, string]> = [
            ["the mother's values, her creatinine chosen", "proto", labs(`?patient=${AMMA}&lab=${CREATININE.points[0]!.observation_id}`)],
            ["a trend under the longest name", "health-long", labs()],
            ["readings printed as bounds", "health-bounds", labs()],
            ["blood pressure's pairs", "health-bp", labs()],
            ["nothing read yet", "health-none", labs()],
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
                expect(await rendersOwnText(page, longNameOf(scenario), ".desktop-sidebar"), "the long name is drawn").toBe(true);
              }
              const report = await deskOverflow(page);
              expect(report.measured, "elements measured").toBeGreaterThan(30);
              expect(report.over).toEqual([]);
              // An ellipsis only on a name the sidebar already allows, never on this screen.
              expect(report.truncated.filter((x) => x.where.length === 0)).toEqual([]);
              expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
            });
          }
        });
      }

      test(`theListAndTheChosenValueShareTheWindow (${name})`, async ({ page }) => {
        await open(page, "proto", labs(`?patient=${AMMA}`));
        const m = await page.evaluate(() => {
          const box = (s: string) => document.querySelector<HTMLElement>(`.desktop-shell ${s}`)!.getBoundingClientRect();
          const screen = box(".desk-labs");
          const list = box(".desk-labs__list");
          const pane = box(".desk-labs__pane");
          return { bottom: window.innerHeight, screen: screen.bottom, list: list.width, listBottom: list.bottom, pane: pane.width, paneRight: pane.right, screenRight: screen.right };
        });
        // desktop.html's `.view.labs { grid-template-columns: 340px minmax(0, 1fr) }`, 300 px below 1200 px.
        expect(m.list, "the list").toBeCloseTo(viewport.width >= 1200 ? 340 : 300, 0);
        expect(Math.abs(m.paneRight - m.screenRight), "the chosen value takes the rest").toBeLessThanOrEqual(1);
        expect(Math.abs(m.screen - m.bottom), "the screen reaches the window's foot").toBeLessThanOrEqual(1);
        expect(Math.abs(m.listBottom - m.bottom), "the list too, scrolling on its own").toBeLessThanOrEqual(1);
      });
    });
  }

  test("aValueIsALinkThatMovesTheDetail", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", labs(`?patient=${AMMA}&lab=${CREATININE.points[0]!.observation_id}`));
    await expect(detail(page).getByRole("heading", { level: 2 })).toHaveText(`${CREATININE.name} ${CREATININE.unit}`);
    await expect(row(page, CREATININE.points[0]!.observation_id)).toHaveAttribute("aria-current", "true");
    // The creatinine due this week: its chip in the detail says so.
    await expect(detail(page).locator(".desk-labs__sub")).toContainText(t("bn", "value.chip.outside.high"));
    await row(page, POTASSIUM.points[0]!.observation_id).click();
    await expect(page).toHaveURL(new RegExp(`[?&]lab=${POTASSIUM.points[0]!.observation_id}(&|$)`));
    await expect(detail(page).getByRole("heading", { level: 2 })).toHaveText(`${POTASSIUM.name} ${POTASSIUM.unit}`);
    await expect(row(page, POTASSIUM.points[0]!.observation_id)).toHaveAttribute("aria-current", "true");
    await expect(row(page, CREATININE.points[0]!.observation_id)).not.toHaveAttribute("aria-current", "true");
    // One reading: desktop.html's sentence, no chart.
    await expect(detail(page).locator("[data-panel=over-time]")).toContainText(t("bn", "desk.labs.once"));
  });

  test("aReadingAsWrittenOpensItsPaperOnTheTalkAndPapersScreen", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", labs(`?patient=${AMMA}&lab=${CREATININE.points[0]!.observation_id}`));
    expect(CREATININE.points[0]!.document_id).toBe(PROTO_PAPERS.creat);
    await detail(page).locator(`[data-panel=as-written] a[data-observation="${CREATININE.points[0]!.observation_id}"]`).click();
    await expect(page).toHaveURL(new RegExp(`/app/preview/home\\?patient=${AMMA}&paper=${PROTO_PAPERS.creat}$`));
    await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.creat);
  });

  test("aTrendOfPlainNumbersIsDrawn", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1280);
    await open(page, "health-some", labs());
    const over = detail(page).locator("[data-panel=over-time]");
    await expect(over.locator(".trend-chart__plot")).toBeVisible();
    await expect(over).toContainText(t("bn", "desk.labs.foot"));
    // Each reading of the series is a way to its own paper.
    const sources = detail(page).locator("[data-panel=as-written] a[data-observation]");
    await expect(sources).toHaveCount(3);
  });

  test("theSidebarsLabValuesItemIsThisScreen", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", labs(`?patient=${AMMA}`));
    const item = page.locator(".desktop-shell .desktop-sidebar__item", { hasText: t("bn", "desk.nav.labs") });
    await expect(item).toHaveAttribute("aria-current", "page");
    await expect(item).toHaveAttribute("href", /^\/app\/preview\/health(\?|$)/);
  });
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("aPreviewHealthPageIsTodaysPhonePage", async ({ page }) => {
    await useScenario(page, "proto");
    const response = await page.goto(labs(`?patient=${AMMA}`));
    expect(response?.status()).toBe(200);
    await expect(page.locator(".desk-labs")).toBeHidden();
    await expect(page.locator("[data-page=health]").first()).toBeVisible();
  });
});
