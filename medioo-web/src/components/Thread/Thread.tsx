import type { ThreadItemJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { AskAnswer } from "@/components/safety/AskAnswer/AskAnswer";
import { call999Words } from "@/components/safety/AskAnswer/call999";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { href } from "@/routing/paths";
import "./Thread.css";

/**
 * The chat thread (plan W5) over GET /app/api/patients/{id}/thread?asks=1 as Java sent it:
 * - a message is what WhatsApp delivered to this account (Java lists only accepted messages, S4, S10, S11): its `body`
 *   as sent, in Medioo's received bubble, and nothing else in that bubble; a failure notice is a message like any other;
 * - a paper is VerdictCard over the card Java sent (the paper JSON's own body), Mio beside it, the card a link to its
 *   paper (inside the preview when Next serves the paper there);
 * - an ask (plan CHAT-web) is a question the family asked in the web chat: the question in the family's sent bubble,
 *   then the answer exactly as Java sent it (AskAnswer: the disclaimer and the red-flag line already in it; the crop of
 *   each prescription line it quotes before it), with the tel:999 link when Java judged `call_999` for this viewer.
 *   Rendered on the server, so it reads without JavaScript.
 * Java sends newest first; the thread reads top to bottom, oldest to newest, as a conversation does, so the newest is
 * beside the composer. Nothing else is chosen here: no item is merged or re-worded. An item of a kind this build does
 * not know (the next additive one) is left out, never drawn as a paper whose card it does not have.
 */
export function Thread({ items, lang, preview }: { items: ThreadItemJson[]; lang: Lang; preview: boolean }) {
  if (items.length === 0) return null;
  return (
    <ol className="thread">
      {[...items].reverse().map((item, k) => {
        switch (item.kind) {
          case "message":
            return (
              <li key={`m${k}`} className="thread__item" data-thread-item="message" data-at={item.at}>
                <MessageBubble surface="recv">{item.body}</MessageBubble>
              </li>
            );
          case "paper":
            return (
              <li key={`p${item.card.id}`} className="thread__item thread__item--paper" data-thread-item="paper" data-at={item.at}>
                <VerdictCard paper={item.card} lang={lang} link={href(`/app/documents/${item.card.id}`, preview)} mio />
              </li>
            );
          case "ask":
            return (
              <li key={`a${item.turn_id}`} className="thread__item thread__item--ask" data-thread-item="ask" data-at={item.at}>
                <MessageBubble surface="sent">{item.question}</MessageBubble>
                <AskAnswer
                  answer={item.answer}
                  call999={item.call_999 ? call999Words(lang) : null}
                  medicines={item.medicines}
                  crop={{ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") }}
                />
              </li>
            );
          default:
            return null;
        }
      })}
    </ol>
  );
}
