import { banglaDigits, formatDate, t, type CopyKey, type Lang } from "@/copy";
import type { CalendarDayJson } from "@/api/types";
import { CHIP_SHAPE, ChipShape } from "@/components/StatusChip/StatusChip";
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

/** The NOW mark is the critical chip's shape, never a colour alone; its word is the legend's. */
const NOW_SHAPE = CHIP_SHAPE["crit-high"];

/** The calendar's own two marks: a paper is a dot, a doctor's date a ring. */
function Mark({ shape }: { shape: "dot" | "ring" }) {
  return (
    <svg className={`month-calendar__mark month-calendar__mark--${shape}`} viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape={shape}>
      <circle cx="12" cy="12" r={shape === "dot" ? 6 : 7.5} />
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
 * the month), weekday headers Saturday → Friday, and on every day cell the status a screen reader hears, built from
 * `calendar.day.status` (on a NOW day followed by the NOW word, which the cell also shows beside the crit shape). The
 * days Java did not send hold nothing, and are drawn empty.
 */
export function MonthCalendar({ month, days, caption, lang }: { month: string; days: CalendarDayJson[]; caption: string; lang: Lang }) {
  const { dates, firstColumn } = monthDays(month);
  const byDate = new Map(days.map((d) => [d.date, d]));
  const nowWord = t(lang, "calendar.legend.now");

  const cells: Array<string | null> = [...Array<null>(firstColumn).fill(null), ...dates];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));

  return (
    <div className="month-calendar">
      {/* A table, not role="grid": a grid promises arrow-key navigation between cells, which a month that only shows
          its days does not offer, and axe holds a gridcell's label to its visible text (label-content-name-mismatch),
          which the day number alone ("1" beside "2026-10-01: …") cannot meet. The screen that makes a day selectable
          puts a button in its cell. */}
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
                const now = day?.papers.some((p) => p.chip === "now") ?? false;
                const status = t(lang, "calendar.day.status", formatDate(lang, date), papers, follows);
                const dayNumber = String(Number(date.slice(8)));
                const classes = ["month-calendar__day", fri && "month-calendar__day--fri", follows > 0 && "month-calendar__day--follow"];
                return (
                  <td key={column} className={classes.filter(Boolean).join(" ")} data-date={date} aria-label={now ? `${status}, ${nowWord}` : status}>
                    <span className="month-calendar__n" aria-hidden="true">
                      {lang === "bn" ? banglaDigits(dayNumber) : dayNumber}
                    </span>
                    <span className="month-calendar__marks" aria-hidden="true">
                      {papers > 0 && <Mark shape="dot" />}
                      {follows > 0 && <Mark shape="ring" />}
                    </span>
                    {now && (
                      <span className="month-calendar__alert" aria-hidden="true">
                        <ChipShape shape={NOW_SHAPE} className="month-calendar__mark month-calendar__mark--crit" />
                        <span className="month-calendar__alert-word">{nowWord}</span>
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
        <li>
          <Mark shape="ring" />
          {t(lang, "calendar.legend.follow")}
        </li>
        <li>
          <ChipShape shape={NOW_SHAPE} className="month-calendar__mark month-calendar__mark--crit" />
          {nowWord}
        </li>
      </ul>
    </div>
  );
}
