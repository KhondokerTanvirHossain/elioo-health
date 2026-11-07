import { formatDate, formatDayMonth, t, type CopyKey, type Lang } from "@/copy";

/*
 * The attention stack's lines (Java's ChatView), with no stylesheet: the phone's stack (AttentionStack) and the desktop
 * conversation's pinned urgent card (Conversation/PinnedUrgent) say them from here, so both say the same words, and a
 * desktop part reads them without linking the stack's stylesheet on a live page (DR-42).
 */

/** A document type's word (TimelineView.typeLabel): `type.<type>` when the copy holds it, else the plain "paper". */
export function typeLabel(lang: Lang, type: string | null): string {
  if (type !== null) {
    try {
      return t(lang, `type.${type}` as CopyKey);
    } catch {
      // A type the copy has no word for is a paper.
    }
  }
  return t(lang, "timeline.paper");
}

const DHAKA_DAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" });

/** "Sent on <day>" for an instant, on the patient's calendar (ChatView.sent: the received instant's Dhaka day). */
export const sent = (lang: Lang, at: string) => t(lang, "timeline.sent", formatDayMonth(lang, DHAKA_DAY.format(new Date(at))));

/** A paper's date as the timeline writes it, with the upload-day note when Java says the paper printed none. */
export const dated = (lang: Lang, date: string, uploadDay: boolean) =>
  formatDate(lang, date) + (uploadDay ? ` ${t(lang, "timeline.uploaddate")}` : "");

