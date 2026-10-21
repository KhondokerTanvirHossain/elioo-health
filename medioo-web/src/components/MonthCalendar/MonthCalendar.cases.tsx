import { cleanup, render } from "@testing-library/react";
import { expect, it } from "vitest";
import { formatDate, t, type Lang } from "@/copy";
import { CHIP_SHAPE } from "@/components/StatusChip/StatusChip";
import busy from "../../../fixtures/api/calendar/busy.json";
import empty from "../../../fixtures/api/calendar/empty.json";
import type { CalendarDayJson } from "@/api/types";
import { MonthCalendar, WEEK } from "./MonthCalendar";

/*
 * The MonthCalendar test bodies, shared by MonthCalendar.test.tsx (the machine's time zone) and MonthCalendar.tz.test.tsx
 * (America/New_York), so the date tests the second file exists for are the same code, not a copy that can drift.
 * Every expectation is computed from B3's generated calendar fixtures and the generated copy; no date is typed here
 * except the one fact the month is checked against: 1 October 2026 is a Thursday.
 */

const LANGS: readonly Lang[] = ["bn", "en"];
const days: CalendarDayJson[] = busy.body.days;
const month = busy.body.month;

const draw = (lang: Lang, d: CalendarDayJson[] = days) =>
  render(<MonthCalendar month={month} days={d} caption="Ma" lang={lang} />).container;

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

/** What a screen reader hears for a day: the status key over the fixture's counts, then the NOW word on a NOW day. */
function expectedStatus(lang: Lang, date: string): string {
  const day = days.find((d) => d.date === date);
  const status = t(lang, "calendar.day.status", formatDate(lang, date), day?.papers.length ?? 0, day?.follow_ups.length ?? 0);
  return day?.papers.some((p) => p.chip === "now") ? `${status}, ${t(lang, "calendar.legend.now")}` : status;
}

/** The tests that depend on the time zone: where the month starts, and the date each cell is labelled with. */
export function monthCalendarDateTests() {
  it("everyDayHasAScreenReaderStatus", () => {
    for (const lang of LANGS) {
      const cells = dayCells(draw(lang));
      expect(cells.map((c) => c.date), `${lang}: one cell per day of ${month}`).toEqual(
        Array.from({ length: 31 }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`),
      );
      const wrong = cells
        .filter(({ date, cell }) => cell.getAttribute("aria-label") !== expectedStatus(lang, date))
        .map(({ date, cell }) => `${lang} ${date}: ${cell.getAttribute("aria-label")}`);
      expect(wrong, "day cells whose aria-label is not their status").toEqual([]);
      cleanup();
    }
    // Bangla digits on bn: the counts are numbers, so t() writes them in Bangla, never "1".
    const first = dayCells(draw("bn"))[0]!.cell.getAttribute("aria-label")!;
    expect(first).not.toMatch(/[0-9]/);
    expect(first).toContain(t("bn", "calendar.day.status", formatDate("bn", `${month}-01`), 1, 0));
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

  it("aNowDayIsShapeAndWordNotOnlyColour", () => {
    const nowDates = days.filter((d) => d.papers.some((p) => p.chip === "now")).map((d) => d.date);
    // Guard: busy.json holds a NOW paper and a day without one, so both directions are exercised.
    expect(nowDates.length, "NOW days in busy.json").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const word = t(lang, "calendar.legend.now");
      const cells = dayCells(draw(lang));
      for (const { date, cell } of cells) {
        const now = nowDates.includes(date);
        const shape = cell.querySelector(`svg[data-shape="${CHIP_SHAPE["crit-high"]}"]`);
        expect(!!shape, `${lang} ${date}: the crit shape ${now ? "is" : "is not"} drawn`).toBe(now);
        expect(cell.textContent!.includes(word), `${lang} ${date}: the NOW word ${now ? "is" : "is not"} written`).toBe(now);
      }
      cleanup();
    }
  });

  it("theLegendNamesAllThreeMarks", () => {
    for (const lang of LANGS) {
      const items = [...draw(lang).querySelectorAll(".month-calendar__legend li")];
      expect(items.map((i) => i.textContent), lang).toEqual([
        t(lang, "calendar.legend.paper"),
        t(lang, "calendar.legend.follow"),
        t(lang, "calendar.legend.now"),
      ]);
      // Three marks, three shapes: the legend tells them apart without colour.
      const shapes = items.map((i) => i.querySelector("svg")?.getAttribute("data-shape"));
      expect(shapes.every(Boolean), `${lang}: every legend item draws its shape`).toBe(true);
      expect(new Set(shapes).size).toBe(3);
      cleanup();
    }
  });

  it("theCaptionIsTheOnlyNameAndTheMonthStillHasEveryDay", () => {
    const root = render(<MonthCalendar month={empty.body.month} days={empty.body.days} caption="Ma · October" lang="en" />).container;
    expect(root.querySelector("caption")?.textContent).toBe("Ma · October");
    expect(dayCells(root)).toHaveLength(31);
    expect(dayCells(root).every(({ cell }) => !cell.querySelector("svg[data-shape]"))).toBe(true);
  });
}
