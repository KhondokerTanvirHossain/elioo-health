import { t, type CopyKey, type Lang } from "@/copy";

/*
 * A paper's type as the bundle names it, in a module with no stylesheet: MonthList draws it, and the desktop search
 * (DesktopSidebar, on every desktop page) names papers with it without carrying MonthList.css.
 */
export const TYPE_KEY: Record<string, CopyKey> = {
  prescription: "type.prescription",
  lab_report: "type.lab_report",
  discharge_summary: "type.discharge_summary",
  imaging_report: "type.imaging_report",
};

/** A paper's type as the bundle names it, else the bundle's word for a paper (C4: the strip and the list say it alike). */
export function typeLabel(lang: Lang, type: string | null): string {
  const key = type === null ? undefined : TYPE_KEY[type];
  return t(lang, key ?? "timeline.paper");
}
