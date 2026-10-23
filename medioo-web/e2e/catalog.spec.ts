import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import {
  axeReport,
  boundaryReport,
  BRAND,
  cspViolations,
  expectSchemeIsPainted,
  imagesReport,
  installCspHook,
  nowRedReport,
  overflowReport,
  smallTextReport,
} from "./support/screen-checks";

/*
 * The component catalog (/app/catalog-test, catalog build only), in the `catalog` project: every registered state, in
 * both languages, under the checks no unit test can make because they are properties of what the browser paints. Each
 * runs at the phone and the desktop width, in both colour schemes. A state registered in src/catalog/states.tsx is
 * covered here without an edit.
 */

const VIEWPORTS = {
  phone: { width: 390, height: 844 },
  desktop: { width: 1280, height: 800 },
} as const;
const SCHEMES = ["light", "dark"] as const;

// The registry's floor, derived as states.test.tsx derives the list: W2c's 13 states (Wordmark 1, MioAvatar 3,
// MessageBubble 3, StatusChip 6), W2e-a's 5 (OtpInput 3, PhoneField 2), W2d-b's 6 (NowBanner), then W2d-a's one
// VerdictCard state per paper fixture (every 200 body B2's contract test wrote), Toast/default and Sheet/open, and
// W2e-b's 9 (MonthCalendar 5, TrendChart 4), and RANGE-web's 5 (TrendChart's printed range). Later PRs raise it.
const documentsDir = join(__dirname, "..", "fixtures", "api", "documents");
const PAPER_FIXTURES = readdirSync(documentsDir)
  .filter((f) => f.endsWith(".json"))
  .filter((f) => (JSON.parse(readFileSync(join(documentsDir, f), "utf8")) as { status: number }).status === 200);
const MIN_STATES = 13 + 5 + 6 + PAPER_FIXTURES.length + 2 + 9 + 5;

/** Every open modal dialog closed (the Sheet/open states), so a whole-page check is not judging a page made inert. */
async function closeSheets(page: Page) {
  await page.evaluate(() => document.querySelectorAll("dialog").forEach((d) => d.close()));
}

/** Loads the catalog with the CSP-violation hook installed from the first byte, every eager image loaded. */
async function open(page: Page) {
  await installCspHook(page);
  const response = await page.goto("/app/catalog-test");
  expect(response?.status(), "the catalog build serves /app/catalog-test").toBe(200);
  await page.waitForLoadState("load");
  // The Sheet/open states open on hydration (showModal), so every check measures them open, in both languages.
  await expect
    .poll(() => page.evaluate(() => document.querySelectorAll('[data-catalog-state="Sheet/open"] dialog:modal').length), {
      message: "both Sheet/open states are open as modals (showModal)",
      timeout: 10_000,
    })
    .toBe(2);
}

for (const [layout, viewport] of Object.entries(VIEWPORTS)) {
  for (const colorScheme of SCHEMES) {
    test.describe(`${layout} ${colorScheme}`, () => {
      test.use({ viewport, colorScheme });

      test("everyRegisteredStateRenders", async ({ page }) => {
        await open(page);
        const registered = Number(await page.locator("main").getAttribute("data-catalog-states"));
        expect(PAPER_FIXTURES.length, "paper fixtures read").toBeGreaterThan(0);
        expect(registered, "registered states").toBeGreaterThanOrEqual(MIN_STATES);
        const sections = await page.$$eval("[data-catalog-state]", (els) =>
          els.map((el) => {
            const h2 = el.querySelector(":scope > h2");
            const rendered = [...el.children].filter((c) => c !== h2);
            const box = rendered.reduce((w, c) => Math.max(w, c.getBoundingClientRect().width), 0);
            return {
              state: el.getAttribute("data-catalog-state")!,
              lang: el.getAttribute("lang"),
              box,
              elements: rendered.length,
              empty: el.hasAttribute("data-catalog-empty"),
            };
          }),
        );
        expect(sections.length, "one section per state per language").toBe(registered * 2);
        expect(sections.length).toBeGreaterThanOrEqual(MIN_STATES * 2);
        const byState = new Map<string, string[]>();
        for (const s of sections) byState.set(s.state, [...(byState.get(s.state) ?? []), s.lang ?? "none"]);
        expect(byState.size, "distinct states").toBe(registered);
        expect([...byState].filter(([, langs]) => langs.join() !== "bn,en"), "states not shown once in bn, then en").toEqual([]);
        expect(
          sections.filter((s) => !s.empty && s.box === 0).map((s) => `${s.state} (${s.lang})`),
          "states that render nothing",
        ).toEqual([]);
        // A state marked empty (the banner Java says not to show, one reading that is no chart) paints no element at all,
        // in either language.
        expect(
          sections.filter((s) => s.empty && s.elements > 0).map((s) => `${s.state} (${s.lang}): ${s.elements} elements`),
          "empty states that render something",
        ).toEqual([]);
        // The exact list is states.test.tsx's (onlyTheListedStatesAreEmpty); this proves those states were on the page.
        expect(sections.filter((s) => s.empty).map((s) => s.state)).toEqual([
          "NowBanner/hidden",
          "NowBanner/hidden",
          "TrendChart/one-point",
          "TrendChart/one-point",
        ]);
        // The registry's exact list is src/catalog/states.test.tsx's: this page's own count above cannot see a state
        // dropped from the registry, only one the page failed to render.
      });

      test("everyVisibleImageIsADecodedBrandFile", async ({ page }) => {
        // A box with a width says nothing about an image: an <img width=48> whose file 404s or fails to decode still
        // has one. So every visible image is checked by its content: loaded, decoded, and one of the approved files.
        await open(page);
        // Mio's eager images can sit below the fold; the report scrolls each into view and waits on every one of them.
        const images = await imagesReport(page, "[data-catalog-state]");
        const bad = images
          .filter((i) => !i.complete || i.natural === 0 || !BRAND.includes(i.path))
          .map((i) => `${i.state}: ${i.path || "no currentSrc"} complete=${i.complete} naturalWidth=${i.natural}`);
        expect(bad).toEqual([]);
        // Guard: the images the W2c states draw were all seen, so the empty list above is not vacuous. Two per
        // language for Wordmark is one: the lazy theme overrides are hidden without data-theme.
        const seen = new Set(images.map((i) => `${i.state} ${i.path}`));
        for (const want of [
          "MioAvatar/32 /brand/mio-head.webp",
          "MioAvatar/48-still /brand/mio-head.webp",
          "MioAvatar/96 /brand/mio.webp",
          `Wordmark/default /brand/medioo-${colorScheme}.svg`,
        ]) {
          expect(seen, "visible, decoded images").toContain(want);
        }
      });

      test("axeReportsNoViolation", async ({ page }) => {
        await open(page);
        // The theme under test is the one painted (reading.spec.ts's guard): the body paints the active scheme's --bg,
        // and the other scheme paints a different one.
        await expectSchemeIsPainted(page, colorScheme);

        // A modal sheet makes the rest of the page inert, which an accessibility check must not judge as the page the
        // family meets: the whole page is judged with the sheets closed, then each open sheet on its own, in the top layer.
        await closeSheets(page);
        const results = await axeReport(page);
        expect(results.violations).toEqual([]);
        // An analysis that checked nothing would also report nothing.
        expect(results.passes).toBeGreaterThan(0);

        for (const lang of ["bn", "en"]) {
          const sheet = `[data-catalog-state="Sheet/open"][lang="${lang}"] dialog`;
          await page.evaluate((selector) => document.querySelector<HTMLDialogElement>(selector)!.showModal(), sheet);
          const sheetResults = await axeReport(page, sheet);
          expect(sheetResults.violations.map((v) => `Sheet/open (${lang}) ${v}`)).toEqual([]);
          expect(sheetResults.passes, `axe checked the open sheet (${lang})`).toBeGreaterThan(0);
          await closeSheets(page);
        }
      });

      test("noElementOverflowsItsBox", async ({ page }) => {
        await open(page);
        // Each section: the heading and at least one element of the state itself, or the inner loop measured nothing
        // there. A state marked empty renders nothing by design, and everyRegisteredStateRenders holds it to that.
        const result = await overflowReport(page, "[data-catalog-state]");
        expect(result.over).toEqual([]);
        expect(result.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(result.page[1] + 1);
        // Guards: every section measured at least one element of its state, the floor of sections was walked
        // (the derived floor × 2 languages; states.test.tsx holds the exact list), and the long names among them: the
        // unbroken bubble (W2c) and the NOW banner's title with a long name, spaced and unbroken (W2d-b).
        expect(result.empty, "sections whose state has no element to measure").toEqual([]);
        expect(result.measured.length).toBeGreaterThanOrEqual(MIN_STATES * 2);
        expect(result.measured).toContain("MessageBubble/long-unbroken");
        expect(result.measured).toContain("NowBanner/long-name");
        expect(result.measured).toContain("NowBanner/long-unbroken");
        // W2e-b: the calendar's caption is the one place it names a patient, long and long without a space.
        expect(result.measured).toContain("MonthCalendar/long-name");
        expect(result.measured).toContain("MonthCalendar/long-unbroken");
      });

      test("onlyNowComponentsPaintTheNowRed", async ({ page }) => {
        await open(page);
        const result = await nowRedReport(page, "[data-catalog-state]");
        expect(result.now.length, "the NOW tokens resolved").toBeGreaterThanOrEqual(3);
        expect(result.bad).toEqual([]);
        // Guard: the red was found where it belongs (the critical chips, the NOW banner and the NOW verdict cards), so
        // the empty list above is not vacuous.
        expect(result.painted).toEqual(
          expect.arrayContaining([
            "StatusChip/crit-high",
            "StatusChip/crit-low",
            "NowBanner/one-patient",
            "NowBanner/abroad",
            "VerdictCard/now-bd",
            "VerdictCard/now-abroad",
          ]),
        );
        // And never on the banner Java said not to show.
        expect(result.painted).not.toContain("NowBanner/hidden");
        // R2 (DR-48): the calendar's NOW marks are among the allowed reds.
        expect(result.painted).toEqual(expect.arrayContaining(["MonthCalendar/busy-month", "MonthCalendar/crowded-month"]));
      });

      test("theTrendRangeIsPaintedPaleAndNeutralAndLeavesTheMarkersAlone", async ({ page }) => {
        // PO ruling R3 (DR-48), on what the browser paints: every band is filled with the pale neutral --sep and
        // outlined by two --label3 lines at its own top and bottom (the fill decorates; the outline is what is seen,
        // 3:1 on the page), every limit line is a --label3 line, never a status colour; the legend's keys match; and no
        // marker paints the band's fill, so the range colours no reading.
        await open(page);
        const report = await page.evaluate(() => {
          const swatch = document.createElement("div");
          document.body.append(swatch);
          const resolve = (prop: string, token: string) => {
            swatch.style.setProperty(prop, `var(${token})`);
            const value = getComputedStyle(swatch).getPropertyValue(prop);
            swatch.style.removeProperty(prop);
            return value;
          };
          const fill = resolve("fill", "--sep");
          const fillBg = resolve("background-color", "--sep");
          const edge = resolve("stroke", "--label3");
          const edgeBorder = resolve("border-top-color", "--label3");
          swatch.remove();
          const bad: string[] = [];
          const counts = { band: 0, bandBound: 0, edge: 0, limit: 0, marker: 0, keyBand: 0, keyEdge: 0 };
          for (const section of document.querySelectorAll<HTMLElement>('[data-catalog-state^="TrendChart/"]')) {
            const at = `${section.dataset.catalogState} ${section.lang}`;
            const plot = section.querySelector<SVGSVGElement>(".trend-chart__plot");
            for (const el of section.querySelectorAll<SVGRectElement>(".trend-chart__plot .trend-chart__band")) {
              counts.band++;
              const v = getComputedStyle(el).fill;
              if (v !== fill) bad.push(`${at}: band fill ${v}, not ${fill}`);
              // Its two bounds, painted: a --label3 line at the band's own top and one at its bottom, across its width.
              const ids = el.getAttribute("data-range-for")!;
              const box = { x: el.x.baseVal.value, y: el.y.baseVal.value, w: el.width.baseVal.value, h: el.height.baseVal.value };
              const bounds = [...(plot?.querySelectorAll<SVGLineElement>("line.trend-chart__edge") ?? [])].filter((l) => l.getAttribute("data-bound-for") === ids);
              const want = [box.y, box.y + box.h];
              const got = bounds.map((l) => [l.y1.baseVal.value, l.y2.baseVal.value, l.x1.baseVal.value, l.x2.baseVal.value]);
              const close = (a: number, b: number) => Math.abs(a - b) < 1e-3;
              for (const y of want) {
                const line = bounds[got.findIndex(([y1, y2, x1, x2]) => close(y1!, y) && close(y2!, y) && close(x1!, box.x) && close(x2!, box.x + box.w))];
                if (!line) {
                  bad.push(`${at}: band ${ids.slice(-3)} has no bound line at y=${y}`);
                  continue;
                }
                const s = getComputedStyle(line);
                if (s.stroke !== edge) bad.push(`${at}: band ${ids.slice(-3)} bound stroke ${s.stroke}, not ${edge}`);
                if (parseFloat(s.strokeWidth) < 1.5) bad.push(`${at}: band ${ids.slice(-3)} bound ${s.strokeWidth} wide`);
                if (s.visibility !== "visible" || s.display === "none" || Number(s.opacity) < 1 || Number(s.strokeOpacity) < 1) {
                  bad.push(`${at}: band ${ids.slice(-3)} bound not painted in full`);
                }
                counts.bandBound++;
              }
            }
            for (const el of section.querySelectorAll(".trend-chart__plot .trend-chart__edge")) {
              counts.edge++;
              const s = getComputedStyle(el);
              if (s.stroke !== edge) bad.push(`${at}: edge stroke ${s.stroke}, not ${edge}`);
              if (el.tagName.toLowerCase() !== "line") bad.push(`${at}: an edge drawn as <${el.tagName.toLowerCase()}>`);
              if (el.hasAttribute("data-range-for")) counts.limit++;
            }
            for (const el of section.querySelectorAll(".trend-chart__marker, .trend-chart__marker *")) {
              counts.marker++;
              const s = getComputedStyle(el);
              if (s.fill === fill || s.stroke === fill) bad.push(`${at}: <${el.tagName.toLowerCase()}> marker paints the band's fill`);
            }
            for (const el of section.querySelectorAll(".trend-chart__key--band")) {
              counts.keyBand++;
              const k = getComputedStyle(el);
              if (k.backgroundColor !== fillBg) bad.push(`${at}: band key ${k.backgroundColor}, not ${fillBg}`);
              for (const [side, colour, width] of [
                ["top", k.borderTopColor, k.borderTopWidth],
                ["bottom", k.borderBottomColor, k.borderBottomWidth],
              ]) {
                if (colour !== edgeBorder || parseFloat(width!) < 1) bad.push(`${at}: band key ${side} bound ${width} ${colour}, not ${edgeBorder}`);
              }
            }
            for (const el of section.querySelectorAll(".trend-chart__key--edge")) {
              counts.keyEdge++;
              const v = getComputedStyle(el).borderTopColor;
              if (v !== edgeBorder) bad.push(`${at}: edge key ${v}, not ${edgeBorder}`);
            }
          }
          return { bad, counts, fill };
        });
        expect(report.bad).toEqual([]);
        // Guard: bands, lines, markers and both keys were on the page, and the fill resolved to a colour.
        expect(report.fill).toMatch(/^rgba?\(/);
        for (const [what, n] of Object.entries(report.counts)) expect(n, `${what} painted`).toBeGreaterThan(0);
      });

      test("theCalendarPaintsItsNowMarkRedAndItsThisWeekMarkAmberAndNothingElse", async ({ page }) => {
        // PO ruling R2 (DR-48), on what the browser paints, not on class names: a NOW day's octagon is stroked in --now
        // and its word in --now-ink; a THIS_WEEK day's calendar shape and word are --week; the legend's keys match;
        // every other element of the calendar (a routine paper's dot, a doctor's-date ring, a numeral, a day with
        // nothing) paints neither colour, and a THIS_WEEK day never paints the red. "Paints" covers the washes
        // (--now-soft, --week-soft) and the doctor's-date ring, which is a box-shadow round the numeral.
        await open(page);
        const report = await page.evaluate(() => {
          const swatch = document.createElement("div");
          document.body.append(swatch);
          const resolve = (prop: string, token: string) => {
            swatch.style.setProperty(prop, `var(${token})`);
            const value = getComputedStyle(swatch).getPropertyValue(prop);
            swatch.style.removeProperty(prop);
            return value;
          };
          // The exact values the marks must paint, one per token, so a NOW shape stroked in --now-ink or a NOW word
          // coloured --now is reported (both are "red" to the sweep below, but not what R2 asked for).
          const now = resolve("color", "--now");
          const nowInk = resolve("color", "--now-ink");
          const week = resolve("color", "--week");
          // Every spelling of the red and the amber, washes included, for the "nothing else" sweep.
          const both = (token: string) => [resolve("color", token), resolve("background-color", token)];
          const reds = [...new Set(["--now", "--now-ink", "--now-soft"].flatMap(both))];
          const ambers = [...new Set(["--week", "--week-soft"].flatMap(both))];
          swatch.remove();
          const PROPS = [
            "color",
            "background-color",
            "fill",
            "stroke",
            "border-top-color",
            "border-right-color",
            "border-bottom-color",
            "border-left-color",
            "outline-color",
            "text-decoration-color",
            "box-shadow",
          ];
          // A shadow reads "rgb(...) 0px 0px 0px 2px inset", so it is matched by containment; every other property by
          // its exact value.
          const SHADOW = new Set(["box-shadow"]);
          const colourOf = (prop: string, value: string) => {
            const is = (c: string) => (SHADOW.has(prop) ? value.includes(c) : value === c);
            return reds.some(is) ? "red" : ambers.some(is) ? "amber" : null;
          };
          /** Every element under `root` (itself included) that paints red or amber, as `<tag.class> prop: colour`. */
          const painted = (root: Element, skip: (e: Element) => boolean = () => false) => {
            const out: string[] = [];
            for (const el of [root, ...root.querySelectorAll("*")]) {
              if (skip(el)) continue;
              const style = getComputedStyle(el);
              for (const prop of PROPS) {
                const colour = colourOf(prop, style.getPropertyValue(prop));
                if (colour) out.push(`<${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${prop}: ${colour}`);
              }
            }
            return out;
          };
          const bad: string[] = [];
          const counts = { now: 0, week: 0, plain: 0, follow: 0, legend: 0 };
          for (const section of document.querySelectorAll<HTMLElement>('[data-catalog-state^="MonthCalendar/"]')) {
            const label = `${section.dataset.catalogState} ${section.getAttribute("lang")}`;
            for (const cell of section.querySelectorAll("td[data-date]")) {
              const date = cell.getAttribute("data-date");
              const alert = cell.querySelector<HTMLElement>(".month-calendar__alert");
              const urgency = alert?.dataset.urgency ?? null;
              if (urgency === "now" || urgency === "week") {
                counts[urgency]++;
                const want = urgency === "now" ? "red" : "amber";
                const shape = alert!.querySelector("svg")!;
                const word = alert!.querySelector(".month-calendar__alert-word")!;
                // Exact tokens: NOW's shape --now and word --now-ink; THIS_WEEK's shape and word --week.
                const wantShape = urgency === "now" ? now : week;
                const wantWord = urgency === "now" ? nowInk : week;
                const stroke = getComputedStyle(shape).stroke;
                const ink = getComputedStyle(word).color;
                if (stroke !== wantShape) bad.push(`${label} ${date}: the ${urgency} shape is ${stroke}, not ${wantShape}`);
                if (ink !== wantWord) bad.push(`${label} ${date}: the ${urgency} word is ${ink}, not ${wantWord}`);
                if (!word.textContent?.trim()) bad.push(`${label} ${date}: the ${urgency} mark has no word`);
                // Inside the mark, only its own colour; outside it, neither.
                for (const p of painted(alert!)) if (!p.endsWith(want)) bad.push(`${label} ${date} in the ${urgency} mark: ${p}`);
                for (const p of painted(cell, (e) => alert!.contains(e))) bad.push(`${label} ${date} outside the ${urgency} mark: ${p}`);
              } else {
                if (cell.querySelector("[data-shape]")) counts.plain++;
                // A doctor's date is drawn as a ring (a box-shadow) round the numeral; counted on its own so that
                // direction cannot pass with no such day judged.
                if (cell.querySelector(".month-calendar__n--follow")) counts.follow++;
                for (const p of painted(cell)) bad.push(`${label} ${date} (no urgency): ${p}`);
              }
            }
            for (const item of section.querySelectorAll(".month-calendar__legend li")) {
              counts.legend++;
              const shape = item.querySelector("svg")?.getAttribute("data-shape");
              const want = shape === "octagon" ? "red" : shape === "calendar-week" ? "amber" : null;
              const got = painted(item);
              if (want === null) for (const p of got) bad.push(`${label} legend ${shape}: ${p}`);
              else {
                const stroke = getComputedStyle(item.querySelector("svg")!).stroke;
                const wantStroke = want === "red" ? now : week;
                if (stroke !== wantStroke) bad.push(`${label} legend ${shape} is ${stroke}, not ${wantStroke}`);
                for (const p of got) if (!p.endsWith(want)) bad.push(`${label} legend ${shape}: ${p}`);
              }
            }
          }
          return { bad, counts, now, nowInk, week };
        });
        expect(report.bad).toEqual([]);
        // Guards: the tokens resolved to three different colours, and NOW days, THIS_WEEK days, marked days without an
        // urgency (a routine paper, a doctor's date), doctor's dates on their own and legends were all judged, so the
        // empty list is not vacuous.
        expect(new Set([report.now, report.nowInk, report.week]).size, "now, now-ink and week are distinct").toBe(3);
        expect(report.counts.follow, "doctor's dates without an urgency judged").toBeGreaterThan(0);
        expect(report.counts.now, "NOW days judged").toBeGreaterThan(0);
        expect(report.counts.week, "THIS_WEEK days judged").toBeGreaterThan(0);
        expect(report.counts.plain, "marked days without an urgency judged").toBeGreaterThan(0);
        expect(report.counts.legend, "legend keys judged").toBeGreaterThan(0);
      });

      test("everyFieldAndTrackDrawsTheBoundary", async ({ page }) => {
        // Q1 (A), PR #120, on what the browser paints: every field and track is washed with --fill and draws --boundary
        // as an edge, at rest, hovered and focused, with no data-theme (what a family gets: the tokens follow this
        // system scheme) and under data-theme light and dark over it. This sees what the CSS scan in
        // built/now-usage.built.test.ts cannot: source order, a later rule that recolours the edge
        // (`border-color: var(--focus)` on focus), and a class only the markup puts on the element.
        await open(page);
        // The open sheets make the page behind them inert, so no field there could be hovered or focused.
        await closeSheets(page);
        const { controls, bad } = await boundaryReport(page, "[data-catalog-state]");

        // The CSS scan's coverage, derived from what the DOM holds rather than from a list of the classes the author
        // remembered: a field whose own element carries no CONTROL_CLASS class is a field built/now-usage never judges.
        expect(
          controls
            .filter((c) => !c.scanned)
            .map((c) => `${c.state} ${c.tag}.${c.classes.join(".")} carries no CONTROL_CLASS class, so built/now-usage cannot judge it`),
          "fields the built-CSS scan cannot see",
        ).toEqual([]);
        expect(bad).toEqual([]);
        // Guards: fields were checked at this layout and scheme, so the empty lists above are not vacuous, and the
        // W2e-a fields, invalid included, were among them. A later PR that ships a field or a segmented track adds its
        // class here.
        expect(controls.length, "fields and tracks checked").toBeGreaterThan(0);
        const checked = new Set(controls.map((c) => `${c.state} ${c.classes.join(".")}`));
        for (const expected of [
          "OtpInput/empty otp-input",
          "OtpInput/error otp-input",
          "PhoneField/empty phone-field__input",
          "PhoneField/error phone-field__input",
        ]) {
          expect(checked, `${expected} was checked`).toContain(expected);
        }
      });

      test("theCodeFieldValidatesInTheBrowser", async ({ page }) => {
        // The pattern as Chromium compiles it (the v flag), on the element: a pattern invalid under v is dropped and
        // then validates nothing, which a unit test compiling its own RegExp cannot see.
        await open(page);
        // The open sheets make the page behind them inert, so the field could not be filled.
        await closeSheets(page);
        const input = page.locator('[data-catalog-state="OtpInput/empty"][lang="en"] input');
        // ASCII, Bangla (U+09E7 to U+09EC, built from code points so no Bangla literal is typed), too short, not a digit.
        const bangla = Array.from({ length: 6 }, (_, i) => String.fromCodePoint(0x09e7 + i)).join("");
        const values = ["123456", bangla, "12345", "12345a"];
        const verdicts: Array<{ value: string; patternMismatch: boolean }> = [];
        for (const value of values) {
          await input.fill(value);
          verdicts.push({ value, patternMismatch: await input.evaluate((n) => (n as HTMLInputElement).validity.patternMismatch) });
        }
        expect(verdicts).toEqual([
          { value: values[0], patternMismatch: false },
          { value: values[1], patternMismatch: false },
          { value: values[2], patternMismatch: true },
          { value: values[3], patternMismatch: true },
        ]);
      });

      test("noTextUnderTwelvePixels", async ({ page }) => {
        await open(page);
        const result = await smallTextReport(page, "[data-catalog-state]");
        expect(result.small).toEqual([]);
        expect(result.texts, "text nodes measured").toBeGreaterThan(0);
      });

      test("theWordmarkPaintsTheThemesFile", async ({ page }) => {
        const requested: string[] = [];
        page.on("request", (r) => {
          const path = new URL(r.url()).pathname;
          if (path.startsWith("/brand/medioo-")) requested.push(path);
        });
        await open(page);
        const visible = () =>
          page.$$eval('[data-catalog-state="Wordmark/default"] img', (imgs) =>
            (imgs as HTMLImageElement[])
              .filter((i) => i.getBoundingClientRect().width > 0)
              .map((i) => ({ src: i.currentSrc, natural: i.naturalWidth, complete: i.complete })),
          );
        const file = (scheme: string) => `medioo-${scheme}.svg`;

        // No data-theme: the system scheme picks the file, and only that file crosses the wire.
        const shown = await visible();
        expect(shown.length, "one visible wordmark per language section").toBe(2);
        for (const img of shown) {
          expect(img.src.endsWith(file(colorScheme)), `${img.src} in ${colorScheme}`).toBe(true);
          expect(img.natural, "the file decoded").toBeGreaterThan(0);
        }
        expect([...new Set(requested)], "wordmark files fetched").toEqual([`/brand/${file(colorScheme)}`]);

        // data-theme overrides the system scheme, in CSS: the other theme's file is shown and decodes.
        const other = colorScheme === "dark" ? "light" : "dark";
        await page.evaluate((theme) => document.documentElement.setAttribute("data-theme", theme), other);
        await expect
          .poll(async () => (await visible()).map((i) => i.complete && i.natural > 0 && i.src.endsWith(file(other))))
          .toEqual([true, true]);
      });

      test("theNowBannersPrimaryActionsMeetThePrimaryTarget", async ({ page }) => {
        // The legibility floor: touch targets at least 44 px, primary actions at least 48 px. On the NOW banner the
        // primary actions are the 999 call (in Bangladesh) and opening the paper (abroad its only way forward).
        await open(page);
        const result = await page.evaluate(() => {
          const heights: Record<string, number[]> = {};
          for (const section of document.querySelectorAll<HTMLElement>('[data-catalog-state^="NowBanner/"]')) {
            for (const [kind, selector] of [
              ["call", ".now-banner__call"],
              ["open", ".now-banner__open"],
              ["seen", ".now-banner__seen"],
            ] as const) {
              for (const el of section.querySelectorAll(selector)) {
                const key = `${section.dataset.catalogState} ${kind}`;
                heights[key] = [...(heights[key] ?? []), el.getBoundingClientRect().height];
              }
            }
          }
          return heights;
        });
        const short = (min: number, kinds: string[]) =>
          Object.entries(result)
            .filter(([key]) => kinds.some((k) => key.endsWith(` ${k}`)))
            .flatMap(([key, hs]) => hs.filter((h) => h < min - 0.5).map((h) => `${key} ${h.toFixed(1)}px`));
        expect(short(48, ["call", "open"]), "primary actions under 48 px").toEqual([]);
        expect(short(44, ["seen"]), "the seen button under 44 px").toEqual([]);
        // Guard: the states that carry each action were measured, so the empty lists are about real buttons.
        expect(result["NowBanner/one-patient call"]?.length, "999 calls on NowBanner/one-patient").toBe(2);
        expect(result["NowBanner/abroad open"]?.length, "open links on NowBanner/abroad").toBe(2);
        expect(result["NowBanner/abroad call"], "no 999 call abroad").toBeUndefined();
      });

      test("mioIsStillOnEveryNowBanner", async ({ page }) => {
        // Mio is still on a NOW surface, read from what the browser runs, not from the class names: whatever class or
        // selector would animate him, the computed animation is none.
        await open(page);
        const result = await page.evaluate(() => {
          const moving: string[] = [];
          let measured = 0;
          for (const img of document.querySelectorAll<HTMLElement>('[data-catalog-state^="NowBanner/"] img.mio-avatar')) {
            measured++;
            const name = getComputedStyle(img).animationName;
            if (name !== "none") moving.push(`${img.closest<HTMLElement>("[data-catalog-state]")!.dataset.catalogState}: ${name}`);
          }
          // The other direction: the moving Mio of MioAvatar/32 does report an animation, so "none" above is a reading.
          const hop = document.querySelector<HTMLElement>('[data-catalog-state="MioAvatar/32"] img.mio-avatar');
          return { moving, measured, hop: hop ? getComputedStyle(hop).animationName : null };
        });
        expect(result.moving).toEqual([]);
        // 5 shown NowBanner states × 2 languages (NowBanner/hidden renders nothing).
        expect(result.measured, "Mio avatars measured on NowBanner states").toBeGreaterThanOrEqual(10);
        expect(result.hop, "MioAvatar/32 animates, so the reading can see an animation").toBe("mio-avatar-hop");
      });

      test("theSheetIsModalAndEscapeClosesIt", async ({ page }) => {
        // What jsdom cannot show: the platform's modal. The sheet is in the top layer with focus inside it, the page
        // behind it is inert, and Escape closes the one on top (en, opened last), then the other.
        await open(page);
        const state = () =>
          page.evaluate(() =>
            [...document.querySelectorAll<HTMLDialogElement>('[data-catalog-state="Sheet/open"] dialog')].map((d) => ({
              lang: d.closest("section")!.getAttribute("lang"),
              open: d.open,
              modal: d.matches(":modal"),
              focusInside: d.contains(document.activeElement),
              name: document.getElementById(d.getAttribute("aria-labelledby") ?? "")?.textContent ?? null,
            })),
          );
        const before = await state();
        expect(before.map((d) => [d.lang, d.open, d.modal])).toEqual([
          ["bn", true, true],
          ["en", true, true],
        ]);
        expect(before.every((d) => d.name), "each sheet is named by its title").toBe(true);
        expect(before.find((d) => d.lang === "en")!.focusInside, "focus is inside the top sheet").toBe(true);
        // The page behind is inert: a click on the catalog heading does not reach it (the top layer is hit instead).
        const behind = await page.evaluate(() => {
          const h1 = document.querySelector("h1")!.getBoundingClientRect();
          const hit = document.elementFromPoint(h1.left + 2, h1.top + 2);
          return hit !== null && hit.closest("dialog") !== null;
        });
        expect(behind, "the page behind the sheet is covered").toBe(true);

        await page.keyboard.press("Escape");
        expect((await state()).map((d) => [d.lang, d.open])).toEqual([
          ["bn", true],
          ["en", false],
        ]);
        await page.keyboard.press("Escape");
        expect((await state()).map((d) => d.open)).toEqual([false, false]);
      });

      test("theToastArrivesInARegionAlreadyThere", async ({ page }) => {
        // A live region inserted with its text already inside is often not announced: the region must be in the page
        // first and the text a change to it. Every role="status" region is recorded the moment the parser or React
        // inserts it, with its text at that moment; afterwards the same elements must hold the toast's words.
        await page.addInitScript(() => {
          const w = window as unknown as { __regions: { el: Element; first: string }[] };
          w.__regions = [];
          // One entry per element: a region is seen both as an added node and inside its added ancestor.
          const seen = new WeakSet<Element>();
          new MutationObserver((records) => {
            for (const r of records) {
              for (const n of r.addedNodes) {
                if (!(n instanceof Element)) continue;
                const found = n.matches('[role="status"]') ? [n] : [...n.querySelectorAll('[role="status"]')];
                for (const el of found) {
                  if (seen.has(el)) continue;
                  seen.add(el);
                  w.__regions.push({ el, first: el.textContent ?? "" });
                }
              }
            }
          }).observe(document, { childList: true, subtree: true });
        });
        await open(page);
        const want = { bn: t("bn", "profile.saved"), en: t("en", "profile.saved") };
        await expect
          .poll(() =>
            page.evaluate(() =>
              [...document.querySelectorAll('[data-catalog-state="Toast/default"] [role="status"]')].map((el) => el.textContent),
            ),
          )
          .toEqual([want.bn, want.en]);
        const regions = await page.evaluate(() =>
          (window as unknown as { __regions: { el: Element; first: string }[] }).__regions
            .filter(({ el }) => el.closest('[data-catalog-state="Toast/default"]'))
            .map(({ el, first }) => ({
              lang: el.closest("section")!.getAttribute("lang"),
              first,
              connected: el.isConnected,
              now: el.textContent,
            })),
        );
        // Exactly one region per language was ever inserted (so the text was not delivered in a replacement element),
        // empty when it arrived, and it is that element that now holds the words.
        expect(regions).toEqual([
          { lang: "bn", first: "", connected: true, now: want.bn },
          { lang: "en", first: "", connected: true, now: want.en },
        ]);
      });

      test("noCspViolation", async ({ page }) => {
        await open(page);
        const violations = await cspViolations(page);
        expect(violations, "the violation hook is installed").not.toBeNull();
        expect(violations).toEqual([]);
      });
    });
  }
}

// The narrowest box a component gets: a 320 CSS px phone less the 16 px side gutters, 288 px. The catalog draws each
// state edge to edge, so the viewport is that box: anything scaled to its box, such as an SVG drawn at 100%, is
// smallest here. The 390 px phone run above cannot see it (plan W2e-b).
test.describe("narrowest box 288", () => {
  test.use({ viewport: { width: 288, height: 640 }, colorScheme: "light" });

  test("noTextUnderTwelvePixelsAtTheNarrowestPhone", async ({ page }) => {
    await open(page);
    const result = await smallTextReport(page, "[data-catalog-state]");
    expect(result.small).toEqual([]);
    expect(result.texts, "text nodes measured").toBeGreaterThan(0);
    // Guard: the charts were on the page and measured, so their words were judged at this width.
    const charts = await page.locator('[data-catalog-state^="TrendChart/"] .trend-chart__reading').count();
    expect(charts, "trend readings at 288 px").toBeGreaterThan(0);
  });

  test("noElementOverflowsItsBoxAtTheNarrowestPhone", async ({ page }) => {
    await open(page);
    // The component's own elements only: the section's <h2> is the catalog's identifier ("MonthCalendar/crowded-month
    // (bn)"), scaffolding no family sees, and at 288 px it is wider than the page while every component fits.
    const over = await page.evaluate(() => {
      const out: string[] = [];
      let measured = 0;
      for (const section of document.querySelectorAll<HTMLElement>('[data-catalog-state^="TrendChart/"], [data-catalog-state^="MonthCalendar/"]')) {
        const rendered = [...section.children].filter((c) => c.tagName !== "H2");
        for (const root of rendered) {
          for (const el of [root, ...root.querySelectorAll("*")]) {
            measured++;
            if (el.scrollWidth > el.clientWidth + 1) {
              out.push(`${section.dataset.catalogState} <${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${el.scrollWidth} > ${el.clientWidth}`);
            }
          }
        }
      }
      return { out, measured };
    });
    expect(over.out).toEqual([]);
    expect(over.measured, "component elements measured at 288 px").toBeGreaterThan(0);
  });
});
