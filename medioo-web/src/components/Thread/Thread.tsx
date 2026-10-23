import type { ThreadItemJson } from "@/api/types";
import type { Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { href } from "@/routing/paths";
import "./Thread.css";

/**
 * The chat thread (plan W5) over GET /app/api/patients/{id}/thread as Java sent it:
 * - a message is what WhatsApp delivered to this account (Java lists only accepted messages, S4, S10, S11): its `body`
 *   as sent, in Medioo's received bubble, and nothing else in that bubble; a failure notice is a message like any other;
 * - a paper is VerdictCard over the card Java sent (the paper JSON's own body), Mio beside it, the card a link to its
 *   paper (inside the preview when Next serves the paper there).
 * Java sends newest first; the thread reads top to bottom, oldest to newest, as a conversation does, so the newest is
 * beside the composer. Nothing else is chosen here: no item is dropped, merged or re-worded.
 */
export function Thread({ items, lang, preview }: { items: ThreadItemJson[]; lang: Lang; preview: boolean }) {
  if (items.length === 0) return null;
  return (
    <ol className="thread">
      {[...items].reverse().map((item, k) =>
        item.kind === "message" ? (
          <li key={`m${k}`} className="thread__item" data-thread-item="message" data-at={item.at}>
            <MessageBubble surface="recv">{item.body}</MessageBubble>
          </li>
        ) : (
          <li key={`p${item.card.id}`} className="thread__item thread__item--paper" data-thread-item="paper" data-at={item.at}>
            <VerdictCard paper={item.card} lang={lang} link={href(`/app/documents/${item.card.id}`, preview)} mio />
          </li>
        ),
      )}
    </ol>
  );
}
