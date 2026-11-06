import { useId } from "react";
import type { AttentionUrgentJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { dated, typeLabel } from "@/components/AttentionStack/lines";
import { UrgencyCard } from "@/components/safety/VerdictCard/VerdictCard";
import { href } from "@/routing/paths";

/**
 * The urgent papers of the attention stack that have no card on the desktop screen (review of plan desktop-parity Task
 * 8, finding 1), pinned at the head of the conversation (desk.tsx). Java filters the stack's urgent rows in SQL, so no
 * number of newer papers can hide a NOW one, while the thread's first page can; once the bar has been seen, this is
 * where the screen still says it. Each is drawn as the phone's stack draws its urgent card, with the same words
 * (AttentionStack/lines): UrgencyCard (the band with Java's fixed sentence, its shape, and not-a-doctor on NOW), the
 * paper's type and day, and Java's `action_key` to the paper's own page, which on the desktop layout opens it in the
 * viewer with its panel and its 999. Under the stack's own heading. Styled by Conversation.css, the conversation's desk
 * stylesheet, so no live page links a stylesheet for it (DR-42). Nothing to pin draws nothing.
 */
export function PinnedUrgent({
  items,
  lang,
  preview,
  possessive,
}: {
  items: AttentionUrgentJson[];
  lang: Lang;
  preview: boolean;
  possessive: string;
}) {
  const headingId = useId();
  if (items.length === 0) return null;
  return (
    <section className="conversation__pinned" aria-labelledby={headingId}>
      <h2 className="conversation__pinned-heading" id={headingId}>
        {t(lang, "attn.heading", possessive)}
      </h2>
      <ol className="conversation__pinned-list">
        {items.map((item) => (
          <li key={item.document_id} className="conversation__pinned-item" data-attention="urgent" data-document={item.document_id}>
            <UrgencyCard level={item.level} lang={lang}>
              <p className="conversation__pinned-line">
                {typeLabel(lang, item.document_type)} · {dated(lang, item.date, item.date_is_upload_day)}
              </p>
              <a className="conversation__pinned-open" href={href(`/app/documents/${item.document_id}`, preview)}>
                {t(lang, item.action_key)}
              </a>
            </UrgencyCard>
          </li>
        ))}
      </ol>
    </section>
  );
}
