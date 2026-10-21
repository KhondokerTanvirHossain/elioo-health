import { cleanup, render } from "@testing-library/react";
import { expect, it } from "vitest";
import { formatDate, t, type Lang } from "@/copy";
import type { CalendarDayJson, PaperJson } from "@/api/types";
import { NowBanner } from "@/components/safety/NowBanner/NowBanner";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import busy from "../../../fixtures/api/calendar/busy.json";
import crowded from "../../../fixtures/api/calendar/crowded.json";
import empty from "../../../fixtures/api/calendar/empty.json";
import catalogNames from "../../../fixtures/catalog/names.json";
import thisWeekPaper from "../../../fixtures/api/documents/this-week-pending.json";
import nowBannerOne from "../../../fixtures/api/now-banner/one.json";
import { MonthCalendar, WEEK } from "./MonthCalendar";

/*
 * The MonthCalendar test bodies, shared by MonthCalendar.test.tsx (the machine's time zone) and MonthCalendar.tz.test.tsx
 * (America/New_York), so the date tests the second file exists for are the same code, not a copy that can drift.
 * Every expectation is computed from B3's generated calendar fixtures and the generated copy, except the facts the month
 * is pinned to by hand: 1 October 2026 is a Thursday, and its first cell is read as that date in each language.
 */

const LANGS: readonly Lang[] = ["bn", "en"];
/** busy: a NOW paper, a follow-up, a routine paper. crowded: two papers and a follow-up on one day, this-week days. */
const MONTHS: ReadonlyArray<[string, { month: string; days: CalendarDayJson[] }]> = [
  ["busy", busy.body],
  ["crowded", crowded.body],
];
const month = busy.body.month;

const draw = (lang: Lang, body: { month: string; days: CalendarDayJson[] } = busy.body, caption = "Ma") =>
  render(<MonthCalendar month={body.month} days={body.days} caption={caption} lang={lang} />).container;

/** The day cells of the grid, in order, each with the column it sits in (0 = the first weekday header). */
function dayCells(root: HTMLElement) {
  const out: Array<{ date: string; column: number; cell: HTMLElement }> = [];
  for (const row of root.querySelectorAll("tbody tr")) {
    [...row.children].forEach((cell, column) => {
      const date = cell.getAttribute("data-date");
      if (date) out.push({ date, column, cell: cell as HTMLElement });
    });
  }
  return out;
}

/** The day's highest urgency, by Java's chip: NOW over this week. */
const urgencyOf = (day: CalendarDayJson | undefined) =>
  day?.papers.some((p) => p.chip === "now") ? "now" : day?.papers.some((p) => p.chip === "this_week") ? "week" : null;

/** What a screen reader hears for a day: the status key of its highest urgency over the fixture's counts. */
function expectedStatus(lang: Lang, days: CalendarDayJson[], date: string): string {
  const day = days.find((d) => d.date === date);
  const urgency = urgencyOf(day);
  const key = urgency === "now" ? "calendar.day.status.now" : urgency === "week" ? "calendar.day.status.week" : "calendar.day.status";
  return t(lang, key, formatDate(lang, date), day?.papers.length ?? 0, day?.follow_ups.length ?? 0);
}

/** The status text of a cell, from the one visually hidden span the cell holds. */
const statusOf = (cell: HTMLElement) => cell.querySelector(".month-calendar__status");

/**
 * The first cell's date as each language writes it, typed by hand rather than computed by formatDate, so a formatDate
 * that slid a day west of UTC cannot move the expectation with it. Bangla is built from code points, as the
 * Bangla-literal lint requires (the day 1, the month October, the year 2026, in Bangla digits and letters).
 */
const FIRST_DAY: Record<Lang, string> = {
  bn: String.fromCodePoint(0x09e7, 0x20, 0x0985, 0x0995, 0x09cd, 0x099f, 0x09cb, 0x09ac, 0x09b0, 0x20, 0x09e8, 0x09e6, 0x09e8, 0x09ec),
  en: "2026-10-01",
};

const DIRECTIONS = ["arrow-up", "arrow-down", "double-arrow-up", "double-arrow-down"];

/** The tests that depend on the time zone: where the month starts, and the date each cell is labelled with. */
export function monthCalendarDateTests() {
  it("everyDayHasAScreenReaderStatus", () => {
    let crowdedDay = 0;
    for (const [name, body] of MONTHS) {
      for (const lang of LANGS) {
        const root = draw(lang, body);
        const cells = dayCells(root);
        expect(cells.map((c) => c.date), `${name} ${lang}: one cell per day of ${month}`).toEqual(
          Array.from({ length: 31 }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`),
        );
        const wrong = cells
          .filter(({ date, cell }) => statusOf(cell)?.textContent !== expectedStatus(lang, body.days, date))
          .map(({ date, cell }) => `${name} ${lang} ${date}: ${statusOf(cell)?.textContent ?? null}`);
        expect(wrong.length, `day cells whose status text is not their status: ${wrong.join("; ")}`).toBe(0);
        for (const { date, cell } of cells) {
          // The status is the cell's content: no author name on the cell, and nothing above the text hides it.
          expect(cell.hasAttribute("aria-label"), `${date}: no aria-label on the cell`).toBe(false);
          for (let e: Element | null = statusOf(cell); e && e !== root; e = e.parentElement) {
            expect(e.getAttribute("aria-hidden"), `${name} ${lang} ${date}: the status is not hidden from a reader`).not.toBe("true");
          }
          const day = body.days.find((d) => d.date === date);
          if (day && day.papers.length > 1 && day.follow_ups.length > 0) crowdedDay++;
        }
        // The first cell is 1 October as each language writes it, by a literal, never by the formatter under test.
        expect(statusOf(cells[0]!.cell)!.textContent!.startsWith(`${FIRST_DAY[lang]}:`), `${name} ${lang}: ${statusOf(cells[0]!.cell)!.textContent}`).toBe(
          true,
        );
        cleanup();
      }
    }
    // Guard: a day with two papers and a follow-up was read, so a count is told apart from "has papers".
    expect(crowdedDay, "crowded days read").toBeGreaterThan(0);
    // Bangla digits on bn: the counts are numbers, so t() writes them in Bangla, never "1".
    const first = statusOf(dayCells(draw("bn"))[0]!.cell)!.textContent!;
    expect(first).not.toMatch(/[0-9]/);
  });

  it("theFirstWeekdayCellIsTheMonthsFirstDay", () => {
    // 1 October 2026 is a Thursday. With the week Saturday → Friday it is the sixth column (index 5), after five blanks.
    const root = draw("bn");
    const first = dayCells(root)[0]!;
    expect(first.date).toBe(`${month}-01`);
    expect(first.column, `column of ${first.date}`).toBe(5);
    const firstRow = root.querySelector("tbody tr")!;
    const blanks = [...firstRow.children].slice(0, first.column);
    expect(blanks.filter((c) => !c.hasAttribute("data-date")).length, "leading blank cells").toBe(5);
    // Every following day sits one column further on, wrapping after Friday.
    const cells = dayCells(root);
    expect(cells.filter((c, i) => c.column !== (5 + i) % 7).map((c) => c.date)).toEqual([]);
  });
}

/** The tests that do not depend on the time zone. */
export function monthCalendarTests() {
  it("theWeekStartsOnSaturdayAsThePrototype", () => {
    for (const lang of LANGS) {
      const headers = [...draw(lang).querySelectorAll("thead th")];
      expect(headers.map((h) => h.textContent), lang).toEqual(WEEK.map((w) => t(lang, w.key)));
      expect(headers[0]!.textContent, `${lang}: the first header`).toBe(t(lang, "calendar.weekday.sat"));
      expect(headers.at(-1)!.textContent, `${lang}: the last header`).toBe(t(lang, "calendar.weekday.fri"));
      expect(headers.map((h) => h.classList.contains("month-calendar__wd--fri")), `${lang}: only Friday is marked`).toEqual([
        false,
        false,
        false,
        false,
        false,
        false,
        true,
      ]);
      cleanup();
    }
  });

  it("aNowDayIsTheOctagonAndTheWordNeverADirection", () => {
    // The NOW octagon the banner draws, path for path: a NOW paper can be NOW for a critically low value or an emergency
    // phrase, so a direction (the critical double arrow) would say "very high" of it.
    const banner = render(<NowBanner banner={nowBannerOne.body} patients={[]} lang="bn" />).container;
    const drawn = banner.querySelectorAll('svg[data-shape="octagon"]');
    expect(drawn, "the banner draws one octagon").toHaveLength(1);
    const octagon = drawn[0]!.innerHTML;
    cleanup();
    let nowDays = 0;
    for (const [name, body] of MONTHS) {
      for (const lang of LANGS) {
        const word = t(lang, "calendar.day.now");
        const root = draw(lang, body);
        expect(root.querySelectorAll(DIRECTIONS.map((d) => `svg[data-shape="${d}"]`).join(", ")), `${name} ${lang}: no direction drawn`).toHaveLength(0);
        for (const { date, cell } of dayCells(root)) {
          const now = urgencyOf(body.days.find((d) => d.date === date)) === "now";
          const shape = cell.querySelector('svg[data-shape="octagon"]');
          expect(!!shape, `${name} ${lang} ${date}: the octagon ${now ? "is" : "is not"} drawn`).toBe(now);
          const written = cell.querySelector(".month-calendar__alert-word")?.textContent === word;
          expect(written, `${name} ${lang} ${date}: the NOW word ${now ? "is" : "is not"} written`).toBe(now);
          if (now) {
            expect(shape!.innerHTML, `${date}: the banner's octagon`).toBe(octagon);
            nowDays++;
          }
        }
        cleanup();
      }
    }
    expect(nowDays, "NOW days drawn").toBeGreaterThanOrEqual(6);
  });

  it("aThisWeekDayIsItsShapeAndWordAndNowOutranksIt", () => {
    // The this-week calendar the paper's band draws (VerdictCard), path for path.
    const card = render(<VerdictCard paper={thisWeekPaper.body as unknown as PaperJson} lang="bn" />).container;
    const week = card.querySelector(".verdict-card__shape")!.innerHTML;
    cleanup();
    const weekOnly = crowded.body.days.filter((d) => urgencyOf(d) === "week").map((d) => d.date);
    const both = crowded.body.days
      .filter((d) => d.papers.some((p) => p.chip === "now") && d.papers.some((p) => p.chip === "this_week"))
      .map((d) => d.date);
    // Guard: crowded.json holds a this-week day alone and one where a NOW paper shares the day.
    expect(weekOnly.length, "this-week days in crowded.json").toBeGreaterThan(0);
    expect(both.length, "days with a NOW and a this-week paper").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const word = t(lang, "calendar.day.week");
      for (const [name, body] of MONTHS) {
        for (const { date, cell } of dayCells(draw(lang, body))) {
          const isWeek = name === "crowded" && weekOnly.includes(date);
          const shape = cell.querySelector('svg[data-shape="calendar-week"]');
          expect(!!shape, `${name} ${lang} ${date}: the this-week shape ${isWeek ? "is" : "is not"} drawn`).toBe(isWeek);
          const written = cell.querySelector(".month-calendar__alert-word")?.textContent === word;
          expect(written, `${name} ${lang} ${date}: the this-week word ${isWeek ? "is" : "is not"} written`).toBe(isWeek);
          if (isWeek) expect(shape!.innerHTML, `${date}: the band's calendar`).toBe(week);
        }
        cleanup();
      }
    }
  });

  it("aDoctorsDateIsARingRoundTheNumeral", () => {
    for (const [name, body] of MONTHS) {
      const follows = body.days.filter((d) => d.follow_ups.length > 0).map((d) => d.date);
      expect(follows.length, `${name}: follow-up days`).toBeGreaterThan(0);
      for (const { date, cell } of dayCells(draw("bn", body))) {
        const ring = cell.querySelector('.month-calendar__n[data-shape="ring"]');
        expect(!!ring, `${name} ${date}: the ring ${follows.includes(date) ? "is" : "is not"} drawn`).toBe(follows.includes(date));
      }
      cleanup();
    }
  });

  it("theLegendNamesEveryMark", () => {
    for (const lang of LANGS) {
      const items = [...draw(lang).querySelectorAll(".month-calendar__legend li")];
      expect(items.map((i) => i.textContent), lang).toEqual([
        t(lang, "calendar.legend.paper"),
        t(lang, "calendar.legend.week"),
        t(lang, "calendar.legend.now"),
        t(lang, "calendar.legend.follow"),
      ]);
      // Four marks, four shapes: the legend tells them apart without colour, with the shapes the cells draw.
      const shapes = items.map((i) => i.querySelector("svg")?.getAttribute("data-shape"));
      expect(shapes, lang).toEqual(["dot", "calendar-week", "octagon", "ring"]);
      cleanup();
    }
  });

  it("theCaptionIsTheOnlyPlaceTheNameAppears", () => {
    const name = catalogNames.names.long;
    for (const lang of LANGS) {
      const root = draw(lang, crowded.body, name);
      expect(root.querySelector("caption")?.textContent).toBe(name);
      const elsewhere: string[] = [];
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (node.parentElement?.closest("caption")) continue;
        if (node.textContent?.includes(name)) elsewhere.push(`text in <${node.parentElement?.tagName.toLowerCase()}>`);
      }
      for (const el of root.querySelectorAll("*")) {
        for (const attr of el.attributes) if (attr.value.includes(name)) elsewhere.push(`${el.tagName.toLowerCase()}[${attr.name}]`);
      }
      expect(elsewhere, `${lang}: the name outside the caption`).toEqual([]);
      cleanup();
    }
  });

  it("anEmptyMonthStillHasEveryDayAndNoMark", () => {
    const root = render(<MonthCalendar month={empty.body.month} days={empty.body.days} caption="Ma · October" lang="en" />).container;
    expect(dayCells(root)).toHaveLength(31);
    expect(dayCells(root).every(({ cell }) => !cell.querySelector("[data-shape]"))).toBe(true);
  });
}
