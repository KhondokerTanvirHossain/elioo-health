import { useId, type ReactNode } from "react";
import type { AttentionItemJson } from "@/api/types";
import { formatDate, formatDayMonth, t, type CopyKey, type Lang } from "@/copy";
import { UrgencyCard } from "@/components/safety/VerdictCard/VerdictCard";
import { href } from "@/routing/paths";
import "./AttentionStack.css";

/** A document type's word (TimelineView.typeLabel): `type.<type>` when the copy holds it, else the plain "paper". */
function typeLabel(lang: Lang, type: string | null): string {
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
const sent = (lang: Lang, at: string) => t(lang, "timeline.sent", formatDayMonth(lang, DHAKA_DAY.format(new Date(at))));

/** A paper's date as the timeline writes it, with the upload-day note when Java says the paper printed none. */
const dated = (lang: Lang, date: string, uploadDay: boolean) =>
  formatDate(lang, date) + (uploadDay ? ` ${t(lang, "timeline.uploaddate")}` : "");

/**
 * The attention stack (plan W5, Java's ChatView.stack) over GET /app/api/patients/{id}/attention, in the order Java
 * sent it, each card with exactly one action, worded by Java's `action_key`, to its paper. An urgent paper is the
 * band of its stored level (UrgencyCard: the NOW red or the THIS_WEEK amber, with the shape and the fixed sentence); a
 * retake, a doctor's date beside the doctor's own words and their crop, and a paper being read are plain cards. An
 * empty stack is silence: no heading and no reassurance. `possessive` is the patient's name as GET /app/api/me
 * writes it in the possessive (NameGrammar), for the heading.
 */
export function AttentionStack({
  items,
  lang,
  preview,
  possessive,
}: {
  items: AttentionItemJson[];
  lang: Lang;
  preview: boolean;
  possessive: string;
}) {
  const headingId = useId();
  if (items.length === 0) return null;
  const action = (item: AttentionItemJson) => (
    <a className="attention-stack__action" href={href(`/app/documents/${item.document_id}`, preview)}>
      {t(lang, item.action_key)}
    </a>
  );
  const card = (item: AttentionItemJson): ReactNode => {
    switch (item.kind) {
      case "urgent":
        return (
          <UrgencyCard level={item.level} lang={lang}>
            <p className="attention-stack__line">
              {typeLabel(lang, item.document_type)} · {dated(lang, item.date, item.date_is_upload_day)}
            </p>
            {action(item)}
          </UrgencyCard>
        );
      case "retake":
      case "reading":
        return (
          <div className="attention-stack__card">
            <p className="attention-stack__title">{t(lang, item.title_key)}</p>
            <p className="attention-stack__line">{sent(lang, item.received_at)}</p>
            {action(item)}
          </div>
        );
      case "follow_up":
        return (
          <div className="attention-stack__card">
            <p className="attention-stack__title">{t(lang, item.title_key, formatDate(lang, item.due_date))}</p>
            {/* Principle 6 (Java's ValueRows.paperQuote): the paper's words under the paper label, beside their crop. */}
            <figure className="attention-stack__quote">
              <figcaption className="attention-stack__paper-label">{t(lang, "paper.label")}</figcaption>
              {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
              <img className="attention-stack__crop" src={item.crop.src} alt="" />
              <blockquote className="attention-stack__words">{item.instruction}</blockquote>
            </figure>
            {action(item)}
          </div>
        );
      default:
        return null;
    }
  };
  return (
    <section className="attention-stack" aria-labelledby={headingId}>
      <h2 className="attention-stack__heading" id={headingId}>
        {t(lang, "attn.heading", possessive)}
      </h2>
      <ol className="attention-stack__list">
        {items.map((item, k) => (
          <li key={`${item.kind}-${item.document_id}-${k}`} className="attention-stack__item" data-attention={item.kind} data-document={item.document_id}>
            {card(item)}
          </li>
        ))}
      </ol>
    </section>
  );
}
