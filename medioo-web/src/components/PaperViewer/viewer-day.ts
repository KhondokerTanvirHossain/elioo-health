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
