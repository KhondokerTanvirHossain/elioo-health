import { expect, test, type Page } from "@playwright/test";
import { formatDayMonth, formatMonthYear, t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS, deskOverflow, hydrated, showSidebar } from "./support/desk-checks";
import { PROTO_PAPERS, PROTO_PATIENTS, longNameOf } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, rendersOwnText } from "./support/screen-checks";

/*
 * The calendar on the desktop layout (plan desktop-parity Phase 2, row C1; desktop.html `calView()`), against the
 * production build and stub Java, on preview pages (/app/preview/timeline, the only requests the layout renders for
 * until the flip). desktop.html's own family (the `proto` scenarios: the mother's October, her doctor's date on the 22nd)
 * and Review Focus 4's forty-five papers under the longest name, at 1440, 1280, 1024 and 900 px, light and dark: axe,
 * CSP, no element wider than its box. Then, in a real browser: the month fills the window; a day is a link that moves
 * the panel; a paper's "open" lands on the talk-and-papers screen with that paper in the viewer; the sidebar's next
 * visit opens its day; the list view; a month back. Below 900 px the same preview page is today's phone page.
 */

const AMMA = PROTO_PATIENTS.amma;
const calendar = (query = "") => `/app/preview/timeline${query}`;
const AMMA_ON = (day: string) => calendar(`?patient=${AMMA}&day=${day}`);

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await expect(page.locator(".desktop-shell .desk-cal"), "the desktop calendar is drawn").toBeVisible();
  // Keys and soft links reach a handler only once React owns the page.
  await hydrated(page);
}

const panel = (page: Page) => page.locator(".desktop-shell .desk-cal__panel");
const day = (page: Page, date: string) => page.locator(`.desktop-shell .desk-cal__day[data-date="${date}"]`);

test.describe("the calendar on the desktop layout", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });
      for (const colorScheme of SCHEMES) {
        test.describe(colorScheme, () => {
          test.use({ colorScheme });
          const pages: Array<[string, string, string]> = [
            ["the mother's October, a doctor's day chosen", "proto", AMMA_ON("2026-10-22")],
            ["the mother's October, a day with a retake chosen", "proto", AMMA_ON("2026-10-20")],
            ["the mother's papers as a list", "proto", calendar(`?patient=${AMMA}&view=list`)],
            ["forty-five papers under the longest name", "papers-forty-five-long", calendar("?day=2026-10-21")],
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
              if (scenario === "papers-forty-five-long") {
                expect(await rendersOwnText(page, longNameOf(scenario), ".desktop-sidebar"), "the long name is drawn").toBe(true);
              }
              const report = await deskOverflow(page);
              expect(report.measured, "elements measured").toBeGreaterThan(50);
              expect(report.over).toEqual([]);
              // An ellipsis only on a day's event (desktop.html's `.ev`) or a name the sidebar already allows, never a name.
              expect(report.truncated.filter((t) => t.where.length > 0)).toEqual([]);
              test.info().annotations.push(...report.truncated.map((t) => ({ type: "ellipsis", description: t.label })));
              expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
            });
          }
        });
      }

      test(`theMonthFillsTheWindowAndEveryDayHasRoom (${name})`, async ({ page }) => {
        await open(page, "proto", AMMA_ON("2026-10-22"));
        const m = await page.evaluate(() => {
          const month = document.querySelector<HTMLElement>(".desktop-shell .desk-cal__month")!.getBoundingClientRect();
          const grid = document.querySelector<HTMLElement>(".desktop-shell .desk-cal__grid")!.getBoundingClientRect();
          const days = [...document.querySelectorAll<HTMLElement>(".desktop-shell .desk-cal__day")].map((d) => d.getBoundingClientRect());
          const aside = document.querySelector<HTMLElement>(".desktop-shell .desk-cal__panel")!.getBoundingClientRect();
          return {
            bottom: window.innerHeight,
            monthBottom: month.bottom,
            gridBottom: grid.bottom,
            heights: days.map((d) => d.height),
            widths: days.map((d) => d.width),
            panel: aside.width,
            gridWidth: grid.width,
          };
        });
        // desktop.html's `.month { flex: 1; grid-auto-rows: minmax(96px, 1fr) }`: the weeks share the window's height.
        expect(Math.abs(m.monthBottom - m.bottom), "the month reaches the window's foot").toBeLessThanOrEqual(1);
        expect(m.gridBottom, "the weeks fill the month").toBeGreaterThanOrEqual(m.monthBottom - 1);
        expect(Math.min(...m.heights), "a day at least 96 px tall").toBeGreaterThanOrEqual(95);
        // desktop.html's `.view.cal { grid-template-columns: minmax(0, 1fr) 340px }` at every width: the panel keeps its
        // 340 px and the seven days share the rest equally.
        expect(m.panel, "the day panel").toBeCloseTo(340, 0);
        expect(Math.max(...m.widths) - Math.min(...m.widths), "the days are equally wide").toBeLessThanOrEqual(1);
        expect(Math.min(...m.widths) * 7, "the days share the month's width").toBeGreaterThanOrEqual(m.gridWidth - 8);
      });
    });
  }

  test("aDayIsALinkThatMovesThePanel", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", AMMA_ON("2026-10-22"));
    await expect(panel(page).getByRole("heading", { level: 2 })).toHaveText(formatDayMonth("bn", "2026-10-22"));
    await expect(panel(page).locator(".desk-cal__card--visit")).toHaveCount(1);
    await day(page, "2026-10-14").click();
    await expect(page).toHaveURL(/[?&]day=2026-10-14(&|$)/);
    await expect(panel(page).getByRole("heading", { level: 2 })).toHaveText(formatDayMonth("bn", "2026-10-14"));
    await expect(day(page, "2026-10-14")).toHaveAttribute("aria-current", "true");
    await expect(day(page, "2026-10-22")).not.toHaveAttribute("aria-current", "true");
    // The 14th holds the creatinine due this week: its card says so with the stored level, as the list does.
    const card = panel(page).locator(`.desk-cal__card[data-document="${PROTO_PAPERS.creat}"]`);
    await expect(card.locator(".urgency-tag")).toBeVisible();
    // The day's event names its urgency with a shape as well as a colour.
    await expect(day(page, "2026-10-14").locator(".desk-cal__ev--week [data-shape='calendar-week']")).toHaveCount(1);
  });

  test("openingAPaperLandsOnTheTalkAndPapersScreenWithThatPaper", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", AMMA_ON("2026-10-15"));
    await panel(page).getByRole("link", { name: t("bn", "desk.cal.open") }).click();
    await expect(page).toHaveURL(new RegExp(`/app/preview/home\\?patient=${AMMA}&paper=${PROTO_PAPERS.rx}$`));
    await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.rx);
  });

  test("theSidebarsNextVisitOpensItsDay", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "proto", calendar(`?patient=${AMMA}&day=2026-10-13`));
    await page.locator(".desktop-shell .desktop-sidebar__visit").click();
    await expect(page).toHaveURL(/[?&]day=2026-10-22(&|$)/);
    await expect(day(page, "2026-10-22")).toHaveAttribute("aria-current", "true");
    const visit = panel(page).locator(".desk-cal__card--visit");
    await expect(visit.getByRole("heading", { level: 3 })).toHaveText(t("bn", "desk.cal.visit.title"));
    await expect(visit.getByRole("link", { name: t("bn", "desk.cal.paper") })).toHaveAttribute("href", new RegExp(`paper=${PROTO_PAPERS.rx}`));
  });

  test("theListViewHoldsTheFiltersAndThePapersAndNoMonth", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1280);
    await open(page, "proto", AMMA_ON("2026-10-22"));
    await page.locator(".desktop-shell .desk-cal__views").getByRole("link", { name: t("bn", "desk.cal.list") }).click();
    await expect(page).toHaveURL(/[?&]view=list(&|$)/);
    const screen = page.locator(".desktop-shell .desk-cal");
    await expect(screen.locator("table")).toHaveCount(0);
    await expect(screen.locator(".desk-cal__panel")).toHaveCount(0);
    await expect(screen.getByRole("heading", { level: 1 })).toHaveText(t("bn", "timeline.docs"));
    await expect(screen.locator(".month-list__row"), "every one of the mother's five papers").toHaveCount(5);
    // A filter keeps the list view.
    await screen.locator(".papers__filter").nth(1).click();
    await expect(page).toHaveURL(/[?&]view=list(&|$)/);
    await expect(page).toHaveURL(/[?&]type=lab(&|$)/);
  });

  test("aMonthBackIsTheMonthJavaAnswers", async ({ page }) => {
    await page.setViewportSize(WIDTHS.w1440);
    await open(page, "papers-busy", calendar());
    await expect(page.locator(".desktop-shell .desk-cal__title")).toHaveText(formatMonthYear("bn", "2026-10-01"));
    await page.locator(".desktop-shell").getByRole("link", { name: t("bn", "desk.cal.prev") }).click();
    await expect(page).toHaveURL(/[?&]month=2026-09(&|$)/);
    await expect(page.locator(".desktop-shell .desk-cal__title")).toHaveText(formatMonthYear("bn", "2026-09-01"));
  });
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("aPreviewCalendarIsTodaysPhonePage", async ({ page }) => {
    await useScenario(page, "proto");
    const response = await page.goto(AMMA_ON("2026-10-22"));
    expect(response?.status()).toBe(200);
    await expect(page.locator(".desk-cal")).toBeHidden();
    await expect(page.locator(".papers").first()).toBeVisible();
  });
});
