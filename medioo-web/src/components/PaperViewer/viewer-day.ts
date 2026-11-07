import { formatDayMonth, t, type Lang } from "@/copy";
import type { PaperResultJson } from "@/api/types";

/**
 * A finished paper's day as the viewer and its strip write it: day and month, marked "(upload date)" when no date was
 * printed and the paper is filed under the day it came (F7: it says so, never passing the upload day off as the
 * printed one), or "(date unsure)" when the printed date was read uncertainly. Both flags are Java's; nothing here
 * compares dates. Same rule as MonthList, LabList and TrendChart.
 */
export function viewerDay(lang: Lang, paper: Pick<PaperResultJson, "date" | "date_is_upload_day" | "date_uncertain">): string {
  const day = formatDayMonth(lang, paper.date);
  if (paper.date_is_upload_day) return t(lang, "trend.date.upload", day);
  if (paper.date_uncertain) return t(lang, "trend.date.uncertain", day);
  return day;
}

/**
 * viewerDay in two parts, for the strip's two lines: the day, and what the mark adds after it (" (upload date)"; empty
 * for a printed day). Joined, they are viewerDay's text exactly, so the mark is never dropped (F7), only set on a line
 * of its own.
 */
export function viewerDayParts(lang: Lang, paper: Pick<PaperResultJson, "date" | "date_is_upload_day" | "date_uncertain">): [string, string] {
  const day = formatDayMonth(lang, paper.date);
  const whole = viewerDay(lang, paper);
  return whole.startsWith(day) ? [day, whole.slice(day.length)] : [whole, ""];
}
