import { banglaDigits, formatDate, t, type CopyKey, type Lang } from "@/copy";
import type { CalendarDayJson } from "@/api/types";
import { ChipShape } from "@/components/StatusChip/StatusChip";
import "./MonthCalendar.css";

/**
 * The week as the phone prototype heads it (phone.html, the papers-tab calendar): Saturday first, Friday last and marked.
 * `day` is the UTC weekday number (`getUTCDay`, Sunday 0) of each column.
 */
export const WEEK: readonly { key: CopyKey; day: number; fri: boolean }[] = [
  { key: "calendar.weekday.sat", day: 6, fri: false },
  { key: "calendar.weekday.sun", day: 0, fri: false },
  { key: "calendar.weekday.mon", day: 1, fri: false },
  { key: "calendar.weekday.tue", day: 2, fri: false },
  { key: "calendar.weekday.wed", day: 3, fri: false },
  { key: "calendar.weekday.thu", day: 4, fri: false },
  { key: "calendar.weekday.fri", day: 5, fri: true },
];

/** A day's urgency: the highest chip among its papers. Java's chip is "now", "this_week" or null; anything else is none. */
export type DayUrgency = "now" | "week" | null;

export function dayUrgency(day: CalendarDayJson | undefined): DayUrgency {
  const chips = day?.papers.map((p) => p.chip) ?? [];
  if (chips.includes("now")) return "now";
  if (chips.includes("this_week")) return "week";
  return null;
}

/**
 * A count with its noun, from the bundle's one and other forms (DR-48): "1 paper", "2 papers". Only exactly one takes
 * the one form, in either language; Bangla's two forms are the same words, so the choice only shows in English.
 */
export function counted(lang: Lang, noun: "papers" | "follows", n: number): string {
  return t(lang, `calendar.count.${noun}.${n === 1 ? "one" : "other"}`, n);
}

/** The sentence a screen reader hears for a day, one bundle key per urgency, so the urgency is never joined on here. */
const STATUS_KEY: Record<"now" | "week" | "none", CopyKey> = {
  now: "calendar.day.status.now",
  week: "calendar.day.status.week",
  none: "calendar.day.status",
};

/**
 * The urgency's shape and word: the NOW octagon and the this-week calendar the paper's band draws, never a direction.
 * And its colour (PO ruling R2, DR-48): the modifier that paints the cell's mark and the legend's key, the NOW red for
 * NOW and the amber for THIS_WEEK. The colour is never the only signal: the shape and the word stay.
 */
const URGENCY: Record<"now" | "week", { shape: "octagon" | "calendar-week"; cell: CopyKey; legend: CopyKey; mark: string; key: string }> = {
  now: { shape: "octagon", cell: "calendar.day.now", legend: "calendar.legend.now", mark: "month-calendar__alert--now", key: "month-calendar__key--now" },
  week: {
    shape: "calendar-week",
    cell: "calendar.day.week",
    legend: "calendar.legend.week",
    mark: "month-calendar__alert--week",
    key: "month-calendar__key--week",
  },
};

/** The calendar's paper mark, a dot; and the legend's ring, the ring a doctor's date draws round its day's numeral. */
function Mark({ shape }: { shape: "dot" | "ring" }) {
  return (
    <svg className={`month-calendar__mark month-calendar__mark--${shape}`} viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape={shape}>
      <circle cx="12" cy="12" r={shape === "dot" ? 6 : 8} />
    </svg>
  );
}

/**
 * The month `YYYY-MM` as its days, by the calendar alone: every date is built from the strings with Date.UTC and read
 * back with UTC getters, so a viewer west of UTC (an abroad share member) sees 1 October on the Thursday it is, never the
 * Wednesday `new Date("2026-10-01").getDay()` gives there. A month that is not `YYYY-MM` throws: Java sends only that.
 */
function monthDays(month: string): { dates: string[]; firstColumn: number } {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m || Number(m[2]) < 1 || Number(m[2]) > 12) throw new Error(`not a month: ${month}`);
  const year = Number(m[1]);
  const index = Number(m[2]) - 1;
  const length = new Date(Date.UTC(year, index + 1, 0)).getUTCDate();
  const firstDay = new Date(Date.UTC(year, index, 1)).getUTCDay();
  const dates = Array.from({ length }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`);
  return { dates, firstColumn: WEEK.findIndex((w) => w.day === firstDay) };
}

/**
 * One month of a patient's papers and doctor's dates (plan W2e-b), the body of B3's
 * `GET /app/api/patients/{id}/calendar`: a table whose caption is the only patient name (the screen passes the name and
 * the month), weekday headers Saturday → Friday, the day's numeral large, a dot for its papers, a ring round the numeral
 * for a doctor's date, and the day's highest urgency as its shape and word (the NOW octagon, the this-week calendar).
 *
 * What a screen reader hears for a day is text in the cell, visually hidden (`calendar.day.status*`, one key per
 * urgency), and everything drawn is aria-hidden: an author name on a static table cell is not reliably announced (NVDA
 * and JAWS browse mode, TalkBack read the cell's content), so the status is the content. The days Java did not send
 * hold nothing, and are drawn empty. A day's NOW mark is the NOW red and its THIS_WEEK mark the amber (R2, DR-48).
 */
export function MonthCalendar({ month, days, caption, lang }: { month: string; days: CalendarDayJson[]; caption: string; lang: Lang }) {
  const { dates, firstColumn } = monthDays(month);
  const byDate = new Map(days.map((d) => [d.date, d]));

  const cells: Array<string | null> = [...Array<null>(firstColumn).fill(null), ...dates];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));

  return (
    <div className="month-calendar">
      {/* A table, not role="grid": a grid promises arrow-key navigation between cells, which a month that only shows
          its days does not offer. The screen that makes a day selectable puts a button in its cell. */}
      <table className="month-calendar__grid">
        <caption className="month-calendar__caption">{caption}</caption>
        <thead>
          <tr>
            {WEEK.map((w) => (
              <th key={w.key} scope="col" className={w.fri ? "month-calendar__wd month-calendar__wd--fri" : "month-calendar__wd"}>
                {t(lang, w.key)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week, w) => (
            <tr key={w}>
              {week.map((date, column) => {
                const fri = WEEK[column]!.fri;
                if (!date) return <td key={column} className="month-calendar__blank" />;
                const day = byDate.get(date);
                const papers = day?.papers.length ?? 0;
                const follows = day?.follow_ups.length ?? 0;
                const urgency = dayUrgency(day);
                const status = t(lang, STATUS_KEY[urgency ?? "none"], formatDate(lang, date), counted(lang, "papers", papers), counted(lang, "follows", follows));
                const dayNumber = String(Number(date.slice(8)));
                return (
                  <td key={column} className={fri ? "month-calendar__day month-calendar__day--fri" : "month-calendar__day"} data-date={date}>
                    <span className="month-calendar__status">{status}</span>
                    <span
                      className={follows > 0 ? "month-calendar__n month-calendar__n--follow" : "month-calendar__n"}
                      aria-hidden="true"
                      {...(follows > 0 ? { "data-shape": "ring" } : {})}
                    >
                      {lang === "bn" ? banglaDigits(dayNumber) : dayNumber}
                    </span>
                    <span className="month-calendar__marks" aria-hidden="true">
                      {papers > 0 && <Mark shape="dot" />}
                    </span>
                    {urgency && (
                      <span className={`month-calendar__alert ${URGENCY[urgency].mark}`} data-urgency={urgency} aria-hidden="true">
                        <ChipShape shape={URGENCY[urgency].shape} className="month-calendar__mark month-calendar__mark--urgency" />
                        <span className="month-calendar__alert-word">{t(lang, URGENCY[urgency].cell)}</span>
                      </span>
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="month-calendar__legend">
        <li>
          <Mark shape="dot" />
          {t(lang, "calendar.legend.paper")}
        </li>
        <li className={URGENCY.week.key}>
          <ChipShape shape={URGENCY.week.shape} className="month-calendar__mark month-calendar__mark--urgency" />
          {t(lang, URGENCY.week.legend)}
        </li>
        <li className={URGENCY.now.key}>
          <ChipShape shape={URGENCY.now.shape} className="month-calendar__mark month-calendar__mark--urgency" />
          {t(lang, URGENCY.now.legend)}
        </li>
        <li>
          <Mark shape="ring" />
          {t(lang, "calendar.legend.follow")}
        </li>
      </ul>
    </div>
  );
}
