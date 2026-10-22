import "server-only";
import { formatDate, t, type CopyKey, type Lang } from "@/copy";
import type { PaperValueJson } from "@/api/types";

/*
 * The few words the paper's server components share (plan W6), each the Java page's own rule (DocumentPages,
 * ValueRows, PageChrome), over what /app/api sent. Nothing here decides a safety line: a chip, a band or a NOW line is
 * always a key Java sent. Server only: the islands take these strings as props.
 */

/** Whether the copy holds `key` in `lang` (a type or a context section Java names by its stored value). */
function known(lang: Lang, key: string): key is CopyKey {
  try {
    t(lang, key as CopyKey);
    return true;
  } catch {
    return false;
  }
}

/** The paper's type as its label, the default label when none was read, or the type as stored (PageChrome.documentType). */
export function typeText(lang: Lang, type: string | null): string {
  if (type === null) return t(lang, "type.default");
  const key = `type.${type}`;
  return known(lang, key) ? t(lang, key) : type;
}

/** A doctor's section label (PageChrome.contextSection); the referral is never shown, and Java never sends it. */
export function sectionText(lang: Lang, section: string): string {
  const key = `ctx.${section}`;
  return known(lang, key) ? t(lang, key) : section;
}

/** The day the paper is filed under, marked "(upload date)" when that is its upload day (DocumentPages.paperDateText). */
export function paperDateText(lang: Lang, date: string, uploadDay: boolean): string {
  return formatDate(lang, date) + (uploadDay ? ` ${t(lang, "timeline.uploaddate")}` : "");
}

/** The paper's date, then the facility only when one was read (DocumentPages.metaLine). */
export function metaLine(lang: Lang, date: string, uploadDay: boolean, facility: string | null): string {
  const day = paperDateText(lang, date, uploadDay);
  return facility === null || facility.trim() === "" ? day : `${day} · ${facility}`;
}

/** "Ma · 64 years", or the name alone when no age is stored. */
export function patientLine(lang: Lang, patient: { name: string; age: number | null }): string {
  return patient.age === null ? patient.name : t(lang, "result.patient", patient.name, patient.age);
}

/** The range as printed: both bounds, one, or none (ValueRows.range). Strings throughout, so the paper's digits are kept. */
export function rangeText(lang: Lang, v: Pick<PaperValueJson, "ref_low" | "ref_high">): string {
  if (v.ref_low !== null && v.ref_high !== null) return t(lang, "value.range", v.ref_low, v.ref_high);
  if (v.ref_low !== null || v.ref_high !== null) return t(lang, "value.range.one", (v.ref_low ?? v.ref_high)!);
  return t(lang, "value.range.none");
}

/** A follow-up's due date as the page shows dates; a value that is not an ISO day is shown as written. */
export function dueText(lang: Lang, due: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(due) ? formatDate(lang, due) : due;
}
