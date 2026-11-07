import { formatDayMonth, formatTime, t, type CopyKey, type Lang } from "@/copy";

/** The day an instant falls on in Dhaka, ISO (yyyy-MM-dd): the family's calendar, as every other page counts days. */
const DHAKA_DAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" });
export function dhakaDay(instantIso: string): string {
  return DHAKA_DAY.format(new Date(instantIso));
}

/** The weekday's short name (MonthCalendar's keys), Sunday first, as Date.getUTCDay counts. */
const WEEKDAY: readonly CopyKey[] = [
  "calendar.weekday.sun",
  "calendar.weekday.mon",
  "calendar.weekday.tue",
  "calendar.weekday.wed",
  "calendar.weekday.thu",
  "calendar.weekday.fri",
  "calendar.weekday.sat",
];

/**
 * The line before the first item of a day in the desktop conversation (plan desktop-parity Task 7; desktop.html
 * `.stamp`): the weekday, day and month of `at` on Dhaka's calendar, in bold, then the time of that first item
 * (formatDayMonth, formatTime). A list item of the conversation's list, so it reads in its place.
 */
export function DaySeparator({ lang, at }: { lang: Lang; at: string }) {
  const day = dhakaDay(at);
  const [y, m, d] = day.split("-").map(Number) as [number, number, number];
  const weekday = WEEKDAY[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]!;
  return (
    <li className="day-separator" data-day={day}>
      <b>
        {t(lang, weekday)}, {formatDayMonth(lang, day)}
      </b>{" "}
      {formatTime(lang, at)}
    </li>
  );
}
