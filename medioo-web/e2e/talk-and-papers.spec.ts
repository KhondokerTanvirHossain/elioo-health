import { expect, test, type Page } from "@playwright/test";
import type { MeJson, NowBannerJson, PaperJson } from "../src/api/types";
import { banglaDigits, t, type CopyKey } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { FIXTURES, PROTO_PAPERS, PROTO_PATIENTS, longNameOf } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook, rendersOwnText } from "./support/screen-checks";
import { SCHEMES, WIDTHS, deskOverflow, hydrated, showSidebar } from "./support/desk-checks";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/*
 * Talk-and-papers (desk.nav.talk) on the desktop layout (plan desktop-parity Task 9 step 1), against the production build and stub Java, on a
 * preview page (/app/preview/..., the only requests the layout renders for until the flip, Task 10) over desktop.html's
 * pretend family (the `proto` scenarios, Task 3's fixtures). At 1440×900, 1280×800, 1024×768 and 900×800, light and
 * dark: axe, CSP, and no element wider than its box (Review Focus 4, with the longest name and the longest strip); then,
 * in a real browser, the strip and ← → move the viewer and the outline together, desk.panel.open opens and closes the panel,
 * the NOW bar's seen button takes the bar away and leaves the card red, and a NOW paper from a link opens with its warning
 * and its panel (Review Focus 1). Below 900 px the same preview page is today's phone page (the phone project).
 */

const body = <T>(file: string): T => (JSON.parse(readFileSync(join(FIXTURES, file), "utf8")) as { body: T }).body;
const PROTO = body<MeJson>("me/proto.json");
/** Java's NOW sentence, as the bar's key and the potassium card's band name it (never typed here). */
const BAR_LINE = t("bn", body<NowBannerJson>("now-banner/proto.json").line_key as CopyKey);
const CARD_LINE = t("bn", body<PaperJson>("documents/proto-potas.json").banner!.line_key as CopyKey);
const ABBU = PROTO.patients.find((p) => p.id === PROTO_PATIENTS.abbu)!;
const home = (query = "") => `/app/preview/home${query}`;
const AMMA_HOME = home(`?patient=${PROTO_PATIENTS.amma}`);
const ABBU_HOME = home(`?patient=${PROTO_PATIENTS.abbu}`);
const POTAS_LINK = `/app/preview/documents/${PROTO_PAPERS.potas}`;

async function open(page: Page, scenario: string, path: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  // The desktop layout is the one drawn: its shell is shown and the phone's is not.
  await expect(page.locator(".desktop-shell"), "the desktop layout is drawn").toBeVisible();
  // Keys and soft links reach a handler only once React owns the page.
  await hydrated(page);
}

/** The paper the viewer shows, the card the conversation outlines, and the strip item marked current. */
async function selection(page: Page) {
  return {
    viewer: await page.locator(".desktop-shell .paper-viewer").getAttribute("data-paper"),
    outlined: await page.locator(".desktop-shell [data-talk-selected]").evaluateAll((els) => els.map((e) => e.getAttribute("data-paper"))),
    current: await page
      .locator('.desktop-shell .paper-strip__item[aria-current="true"]')
      .evaluateAll((els) => els.map((e) => new URL((e as HTMLAnchorElement).href).searchParams.get("paper"))),
  };
}

test.describe("talk-and-papers on the desktop layout", () => {
  // The desktop project (Desktop Chrome) sets the widths here; the phone project (Pixel 7, mobile) checks below 900 px.
  test.skip(({ isMobile }) => isMobile, "the desktop widths are the desktop project's");

  for (const [name, viewport] of Object.entries(WIDTHS)) {
    test.describe(`at ${viewport.width}×${viewport.height}`, () => {
      test.use({ viewport });

      for (const colorScheme of SCHEMES) {
        test.describe(colorScheme, () => {
          test.use({ colorScheme });
          const pages: Array<[string, string, string]> = [
            ["home, the mother's papers", "proto", AMMA_HOME],
            ["home, the father's NOW paper", "proto", ABBU_HOME],
            ["a NOW paper from a link, its panel open", "proto", POTAS_LINK],
            ["the longest name over the longest strip", "proto-long", home()],
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
              if (scenario === "proto-long") {
                // The long name is drawn in the sidebar, the conversation's header and the NOW bar, or overflow was judged on
                // short strings.
                const long = longNameOf(scenario);
                for (const where of [".desktop-sidebar", ".conversation__head", ".now-banner--bar"]) {
                  expect(await rendersOwnText(page, long, where), `the long name is drawn inside ${where}`).toBe(true);
                }
              }
              const report = await deskOverflow(page);
              expect(report.measured, "elements measured").toBeGreaterThan(50);
              expect(report.over).toEqual([]);
              // A name wraps where it is the line's point (the sidebar, the conversation's header, the bar): never an ellipsis.
              expect(report.truncated.filter((t) => t.where.length > 0)).toEqual([]);
              test.info().annotations.push(...report.truncated.map((t) => ({ type: "ellipsis", description: t.label })));
              expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
              if (scenario === "proto-long") {
                // Not vacuous: ten papers are wider than the strip at every width, and the strip scrolls instead of growing.
                expect(report.scrolling.some((c) => c.includes("paper-strip__list")), `the strip scrolls sideways (${report.scrolling.join(", ")})`).toBe(true);
              }
            });
          }
        });
      }

      test(`theViewersUrgencyWordIsWholeOnOneLineInsideItsToolbar (${name})`, async ({ page }) => {
        // The paper's stored urgency in the viewer's toolbar (UrgencyTag): Java's word, drawn whole on one line and inside
        // the toolbar, never broken letter by letter or hanging out of it, whatever the width leaves the title.
        for (const [who, paper] of [
          [PROTO_PATIENTS.abbu, PROTO_PAPERS.potas],
          [PROTO_PATIENTS.amma, PROTO_PAPERS.creat],
        ] as const) {
          await open(page, "proto", home(`?patient=${who}&paper=${paper}`));
          const tag = page.locator(".desktop-shell .paper-viewer__toolbar .urgency-tag");
          await expect(tag, `${paper}: the toolbar's urgency tag`).toHaveCount(1);
          const m = await tag.evaluate((el: HTMLElement) => {
            const word = el.querySelector<HTMLElement>(".urgency-tag__word")!;
            const bar = el.closest(".paper-viewer__toolbar")!.getBoundingClientRect();
            const box = el.getBoundingClientRect();
            const line = parseFloat(getComputedStyle(word).lineHeight);
            return {
              text: word.textContent,
              needs: word.scrollWidth,
              has: word.clientWidth,
              lines: Math.round(word.getBoundingClientRect().height / line),
              inside: box.top >= bar.top - 0.5 && box.bottom <= bar.bottom + 0.5 && box.left >= bar.left - 0.5 && box.right <= bar.right + 0.5,
            };
          });
          expect(m.needs, `${paper}: "${m.text}" is cut (${m.needs} px in ${m.has})`).toBeLessThanOrEqual(m.has + 1);
          expect(m.lines, `${paper}: "${m.text}" is drawn on ${m.lines} lines`).toBe(1);
          expect(m.inside, `${paper}: "${m.text}" hangs out of the toolbar`).toBe(true);
        }
      });

      test(`aStripItemChangesTheViewerAndOutlinesItsCard (${name})`, async ({ page }) => {
        await open(page, "proto", AMMA_HOME);
        // The newest is shown first: the retake (blur).
        expect(await selection(page)).toEqual({ viewer: PROTO_PAPERS.blur, outlined: [PROTO_PAPERS.blur], current: [PROTO_PAPERS.blur] });
        const lipid = page.locator(".desktop-shell .paper-strip__item").filter({ has: page.locator(`img[src*="${PROTO_PAPERS.lipid}"]`) });
        await lipid.click();
        await page.waitForURL((u) => u.searchParams.get("paper") === PROTO_PAPERS.lipid);
        await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.lipid);
        expect(await selection(page)).toEqual({ viewer: PROTO_PAPERS.lipid, outlined: [PROTO_PAPERS.lipid], current: [PROTO_PAPERS.lipid] });
        // The outlined card is the one drawn with the outline.
        await expect(page.locator(`.desktop-shell [data-talk-selected][data-paper="${PROTO_PAPERS.lipid}"] .verdict-card--selected`)).toBeVisible();
      });

      test(`theArrowKeysMoveBetweenPapers (${name})`, async ({ page }) => {
        // The mother's papers, newest first: blur, cbc, rx, creat, lipid. ← is the older paper, → the newer.
        await open(page, "proto", `${AMMA_HOME}&paper=${PROTO_PAPERS.rx}`);
        expect((await selection(page)).viewer).toBe(PROTO_PAPERS.rx);
        await page.locator("body").press("ArrowLeft");
        await page.waitForURL((u) => u.searchParams.get("paper") === PROTO_PAPERS.creat);
        await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.creat);
        expect(await selection(page)).toEqual({ viewer: PROTO_PAPERS.creat, outlined: [PROTO_PAPERS.creat], current: [PROTO_PAPERS.creat] });
        await page.locator("body").press("ArrowRight");
        await page.waitForURL((u) => u.searchParams.get("paper") === PROTO_PAPERS.rx);
        await page.locator("body").press("ArrowRight");
        await page.waitForURL((u) => u.searchParams.get("paper") === PROTO_PAPERS.cbc);
        await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.cbc);
        expect(await selection(page)).toEqual({ viewer: PROTO_PAPERS.cbc, outlined: [PROTO_PAPERS.cbc], current: [PROTO_PAPERS.cbc] });
      });

      test(`seeAllOpensAndClosesThePanel (${name})`, async ({ page }) => {
        await open(page, "proto", `${AMMA_HOME}&paper=${PROTO_PAPERS.creat}`);
        await expect(page.locator(".paper-panel")).toHaveCount(0);
        const card = page.locator(`.desktop-shell [data-talk-selected][data-paper="${PROTO_PAPERS.creat}"]`);
        await card.getByRole("link", { name: t("bn", "desk.panel.open") }).click();
        await page.waitForURL((u) => u.searchParams.get("panel") === "1");
        const panel = page.locator(`.desktop-shell .paper-panel[data-panel="${PROTO_PAPERS.creat}"]`);
        await expect(panel).toBeVisible();
        // The panel lies over the conversation column, inside it.
        const [over, talk] = await Promise.all([panel.boundingBox(), page.locator(".desktop-shell__talk").boundingBox()]);
        expect(over!.x, "the panel starts inside the conversation column").toBeGreaterThanOrEqual(talk!.x - 1);
        expect(over!.x + over!.width, "the panel ends inside the conversation column").toBeLessThanOrEqual(talk!.x + talk!.width + 1);
        await panel.getByRole("link", { name: t("bn", "sheet.close") }).click();
        await page.waitForURL((u) => !u.searchParams.has("panel") && u.searchParams.get("paper") === PROTO_PAPERS.creat);
        await expect(page.locator(".paper-panel")).toHaveCount(0);
        await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.creat);
      });

      test(`theBarsSeenHidesTheBarOnlyAndTheCardStaysRed (${name})`, async ({ page }) => {
        await open(page, "proto", ABBU_HOME);
        const bar = page.locator(".desktop-shell .now-banner--bar");
        await expect(bar).toBeVisible();
        // The bar names the father first, then Java's sentence verbatim.
        await expect(bar.locator(".now-banner__title")).toContainText(ABBU.name);
        await expect(bar.locator(".now-banner__line")).toHaveText(BAR_LINE);
        const card = page.locator(`.desktop-shell [data-talk-item="paper"][data-paper="${PROTO_PAPERS.potas}"] .verdict-card--now`);
        await expect(card).toBeVisible();
        await expect(card.locator('[data-safety="band"]')).toHaveText(CARD_LINE);
        await expect(card.locator('[data-safety="call-999"]')).toHaveAttribute("href", "tel:999");
        await bar.getByRole("button", { name: t("bn", "now.banner.seen") }).click();
        await expect(page.locator(".now-banner--bar"), "the bar is gone").toHaveCount(0);
        // The card is unchanged: still red, its sentence and its 999 still there.
        await expect(card).toBeVisible();
        await expect(card.locator('[data-safety="band"]')).toHaveText(CARD_LINE);
        await expect(card.locator('[data-safety="call-999"]')).toBeVisible();
      });

      test(`aNowPaperFromALinkOpensWithItsWarningAndItsPanel (${name})`, async ({ page }) => {
        // Review Focus 1: an urgent paper opened from a WhatsApp link never shows just the image.
        await open(page, "proto", POTAS_LINK);
        await expect(page.locator(".desktop-shell .now-banner--bar"), "the NOW bar").toBeVisible();
        const card = page.locator(`.desktop-shell [data-talk-item="paper"][data-paper="${PROTO_PAPERS.potas}"] .verdict-card--now`);
        await expect(card, "the red card").toBeVisible();
        await expect(card.locator('[data-safety="call-999"]'), "999 on the card").toHaveAttribute("href", "tel:999");
        const panel = page.locator(`.desktop-shell .paper-panel[data-panel="${PROTO_PAPERS.potas}"]`);
        await expect(panel, "the panel, open").toBeVisible();
        await expect(panel.locator('[data-safety="now-block"]'), "the panel's NOW block").toBeVisible();
        await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.potas);
        // The warning is on screen in the bar and in the panel's band; the card the panel covers keeps its own.
        const sentence = BAR_LINE;
        expect(CARD_LINE, "the card's band and the bar say the same sentence").toBe(BAR_LINE);
        const shown = await page
          .locator(".desktop-shell")
          .getByText(sentence, { exact: true })
          .evaluateAll((els) => els.filter((e) => e.getClientRects().length > 0).length);
        expect(shown, "Java's NOW sentence is on screen").toBeGreaterThanOrEqual(2);
      });
    });
  }

  // The PO's first look at the preview (2026-11-07): the page larger than the design, no way to give the paper or the
  // conversation more room, and the conversation not following the papers.
  test.describe("the PO's details (2026-11-07)", () => {
    /** Whether an element's box overlaps the conversation column's visible area. */
    const inColumn = (page: Page, selector: string) =>
      page.evaluate((sel) => {
        const column = document.querySelector(".desktop-shell .conversation")!.getBoundingClientRect();
        const el = document.querySelector(sel);
        if (el === null) return false;
        const box = el.getBoundingClientRect();
        return box.bottom > column.top + 1 && box.top < column.bottom - 1;
      }, selector);
    const width = (page: Page, selector: string) => page.locator(selector).first().evaluate((e) => e.getBoundingClientRect().width);
    const item = (id: string) => `.desktop-shell [data-talk-item="paper"][data-paper="${id}"] .verdict-card`;

    test("theFittedPageNeverGrowsPastItsOwnWidth", async ({ page }) => {
      // The PO's window (1710 CSS px): the canvas is far wider than a page, which stays at 100%, 640 px.
      await page.setViewportSize({ width: 1710, height: 1000 });
      await open(page, "proto", AMMA_HOME);
      expect(await width(page, ".desktop-shell__paper .paper-viewer__canvas"), "the canvas is wider than a page").toBeGreaterThan(800);
      expect(await width(page, ".desktop-shell__paper .paper-viewer__page"), "the page at its own width").toBeCloseTo(640, 0);
      await expect(page.locator(".desktop-shell .paper-viewer__zoom-level"), "and says so").toHaveText(banglaDigits("100%"));
      // A narrow window: the page fills the canvas, smaller than 100%.
      await page.setViewportSize(WIDTHS.w900);
      await expect.poll(() => width(page, ".desktop-shell__paper .paper-viewer__page")).toBeLessThan(640);
    });

    test("theConversationFollowsThePaperInTheViewer", async ({ page }) => {
      await page.setViewportSize(WIDTHS.w1440);
      await open(page, "proto", AMMA_HOME);
      // The column at its end (the newest), where the oldest paper's card is out of sight, so only a follow brings it.
      // Set here, not left to the opening, so this checks the following alone.
      await page.locator(".desktop-shell .conversation").evaluate((c) => {
        c.scrollTop = c.scrollHeight;
      });
      expect(await inColumn(page, item(PROTO_PAPERS.lipid)), "the oldest card starts out of sight").toBe(false);
      await page.locator(".desktop-shell .paper-strip__item").filter({ has: page.locator(`img[src*="${PROTO_PAPERS.lipid}"]`) }).click();
      await page.waitForURL((u) => u.searchParams.get("paper") === PROTO_PAPERS.lipid);
      await expect.poll(() => inColumn(page, item(PROTO_PAPERS.lipid)), { message: "the conversation came to the chosen paper's card" }).toBe(true);
      // And back, with the keys: → the newer papers, until the newest; the column follows each time.
      for (const id of [PROTO_PAPERS.creat, PROTO_PAPERS.rx, PROTO_PAPERS.cbc, PROTO_PAPERS.blur]) {
        await page.locator("body").press("ArrowRight");
        await page.waitForURL((u) => u.searchParams.get("paper") === id);
        await expect.poll(() => inColumn(page, item(id)), { message: `followed to ${id}` }).toBe(true);
      }
    });

    test("theNowBarLeavesTheConversationOnThePaperInView", async ({ page }) => {
      // Finding 10 of the board: the bar held the column at its oldest item, so the red card could sit below the fold.
      // The bar lies across both columns and stays in sight; the column opens on the paper in view.
      await page.setViewportSize(WIDTHS.w1440);
      // The mother's newest paper, at the foot of a thread taller than the column, while the father's NOW bar is shown.
      await open(page, "proto", `${AMMA_HOME}&paper=${PROTO_PAPERS.blur}`);
      await expect(page.locator(".desktop-shell .now-banner--bar"), "the bar").toBeVisible();
      const column = page.locator(".desktop-shell .conversation");
      expect(await column.evaluate((c) => c.scrollHeight > c.clientHeight * 2), "a thread the column cannot show at once").toBe(true);
      await expect.poll(() => inColumn(page, item(PROTO_PAPERS.blur)), { message: "the paper in view's card in sight" }).toBe(true);
      // The father's red card, opened from his page, in sight with its sentence.
      await open(page, "proto", `${ABBU_HOME}&paper=${PROTO_PAPERS.potas}`);
      await expect.poll(() => inColumn(page, item(PROTO_PAPERS.potas)), { message: "the red card in sight" }).toBe(true);
      await expect(page.locator(`${item(PROTO_PAPERS.potas)}.verdict-card--now [data-safety="band"]`)).toHaveText(CARD_LINE);
      // Its band, Java's sentence, is in sight itself: below the column's sticky header and above its composer, not
      // under either (the card is taller than the column, so it comes in at its top).
      const band = () =>
        page.evaluate((sel) => {
          const column = document.querySelector(".desktop-shell .conversation")!;
          const head = column.querySelector(".conversation__head")!.getBoundingClientRect();
          const foot = column.querySelector(":scope > .composer")!.getBoundingClientRect();
          const b = document.querySelector(sel)!.getBoundingClientRect();
          return b.top >= head.bottom - 1 && b.bottom <= foot.top + 1;
        }, `${item(PROTO_PAPERS.potas)} [data-safety="band"]`);
      await expect.poll(band, { message: "the band is under neither the header nor the composer" }).toBe(true);
    });

    test("theDividerGivesEitherColumnRoomAndIsRemembered", async ({ page }) => {
      await page.setViewportSize(WIDTHS.w1440);
      await open(page, "proto", AMMA_HOME);
      const divider = page.getByRole("separator", { name: t("bn", "desk.resize") });
      const talk = () => width(page, ".desktop-shell__talk");
      const paper = () => width(page, ".desktop-shell__paper");
      const columns = await width(page, ".desktop-shell__columns");
      expect(await talk(), "the design's width").toBeCloseTo(400, 0);
      const paperBefore = await paper();
      // Dragged 120 px to the left: the conversation 120 wider, the paper 120 narrower, and the page fitted again.
      const box = (await divider.boundingBox())!;
      const x = box.x + box.width / 2;
      const y = box.y + box.height / 2;
      await page.mouse.move(x, y);
      await page.mouse.down();
      await page.mouse.move(x - 60, y, { steps: 4 });
      await page.mouse.move(x - 120, y, { steps: 4 });
      await page.mouse.up();
      expect(await talk()).toBeCloseTo(520, 0);
      expect(await paper()).toBeCloseTo(paperBefore - 120, 0);
      await expect.poll(() => width(page, ".desktop-shell__paper .paper-viewer__page")).toBeLessThanOrEqual(
        (await width(page, ".desktop-shell__paper .paper-viewer__canvas")) + 0.5,
      );
      // Remembered on this browser.
      await page.reload();
      await page.waitForLoadState("load");
      await expect.poll(talk, { message: "the width comes back" }).toBeCloseTo(520, 0);
      // The keys: End, the widest, leaving the paper 420 px; Home, the narrowest; Enter, the design's width again.
      await divider.focus();
      await divider.press("End");
      expect(await talk()).toBeCloseTo(columns - 420, 0);
      expect(await paper()).toBeCloseTo(420, 0);
      await divider.press("Home");
      expect(await talk()).toBeCloseTo(320, 0);
      await divider.press("End");
      // A narrower window never lets a remembered width squeeze the paper below 420 px.
      await page.setViewportSize(WIDTHS.w900);
      await expect.poll(paper).toBeGreaterThanOrEqual(419.5);
      expect(await talk()).toBeGreaterThanOrEqual(319.5);
      await divider.press("Enter");
      await expect.poll(talk, { message: "Enter: the design's 360 px below 1200" }).toBeCloseTo(360, 0);
      await page.setViewportSize(WIDTHS.w1440);
      await expect.poll(talk, { message: "and 400 from 1200" }).toBeCloseTo(400, 0);
    });

    test("everyStripLabelIsOneLineAndTheThumbnailsStayInARow", async ({ page }) => {
      await page.setViewportSize(WIDTHS.w1440);
      await open(page, "proto", AMMA_HOME);
      const lines = await page.locator(".desktop-shell .paper-strip__day, .desktop-shell .paper-strip__mark").evaluateAll((els) =>
        els.map((e) => ({
          text: e.textContent,
          height: e.getBoundingClientRect().height,
          font: parseFloat(getComputedStyle(e).fontSize),
          cut: e.scrollWidth > e.clientWidth + 1,
        })),
      );
      expect(lines.some((l) => l.text?.trim().startsWith("(")), "a mark line is among them").toBe(true);
      for (const l of lines) expect(l.height, `"${l.text}" on one line`).toBeLessThan(l.font * 1.6);
      // And whole: F7's mark is read, never cut to "(upload…".
      for (const l of lines) expect(l.cut, `"${l.text}" is cut`).toBe(false);
      const tops = await page.locator(".desktop-shell .paper-strip__thumb").evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)));
      expect(new Set(tops).size, "every thumbnail at the same height").toBe(1);
    });
  });
  // The papers page on a preview page is the desktop calendar now (plan desktop-parity C1): desk-calendar.spec.ts judges
  // it, Review Focus 4's forty-five papers under the longest name included.
});

test.describe("below 900 px", () => {
  test.skip(({ isMobile }) => !isMobile, "the phone project's 390 px");

  test("aPreviewHomeIsTodaysPhonePage", async ({ page }) => {
    await useScenario(page, "proto");
    const response = await page.goto(AMMA_HOME);
    expect(response?.status()).toBe(200);
    // Both shells are in the document on a preview page; below 900 px only today's is drawn.
    await expect(page.locator(".desktop-shell")).toBeHidden();
    await expect(page.locator('[data-page="chat"]')).toBeVisible();
    await expect(page.locator("a.tab-bar__link:visible", { hasText: t("bn", "tab.chat") })).toHaveCount(1);
    // The NOW banner is the block, never the bar.
    await expect(page.locator('section[role="alert"]:visible')).toHaveCount(1);
    await expect(page.locator(".now-banner--bar:visible")).toHaveCount(0);
  });
});
