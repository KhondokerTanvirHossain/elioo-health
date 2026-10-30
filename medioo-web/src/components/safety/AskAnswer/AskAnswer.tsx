import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import "./AskAnswer.css";

/**
 * Medioo's answer to a question (plan CHAT-web, DR-50), drawn the same way by the composer as it arrives and by the
 * thread once it is stored: Java's `answer` as text in Medioo's received bubble (React escapes it; never markup), the
 * bubble holding that text and nothing else, and under it the tel:999 link only when Java says `call_999` (F7: a
 * Bangladeshi phone only). `call999` is the link's words (call999Words, resolved by the server parent: this ships no
 * copy), or null for no link. No "use client": the thread renders it on the server, so a stored answer reads without
 * JavaScript; the composer imports it into its island.
 */
export function AskAnswer({ answer, call999 }: { answer: string; call999: string | null }) {
  return (
    <div className="ask-answer" data-ask-answer>
      <MessageBubble surface="recv">{answer}</MessageBubble>
      {call999 !== null && (
        <a className="ask-answer__call" href="tel:999">
          {call999}
        </a>
      )}
    </div>
  );
}
