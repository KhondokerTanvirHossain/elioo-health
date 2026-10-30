"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { apiSend, type ApiResult } from "@/api/browser";
import type { AskJson } from "@/api/types";
import { AskAnswer } from "@/components/safety/AskAnswer/AskAnswer";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { href } from "@/routing/paths";
import "./Composer.css";

/**
 * Every word the composer says, resolved on the server (home/chat-words.ts): this island ships no copy.
 * - `field`: the field's name (`chat.field`);
 * - `send`: the send button (`add.send`);
 * - `standing`: the line under the bar as the server renders it, without JavaScript, where there is no field
 *   (`chat.ask.nojs`: how to ask);
 * - `hint`: that line once JavaScript runs and the field is there (`chat.ask.hint`: answers come only from the papers);
 * - `reply`: what Medioo says when no answer came (a refusal, a dead network, the timeout): `chat.ask.reply`, then
 *   `safety.red_flag_line` appended by code, as WhatsApp appends it to `wa.fallback`;
 * - `pending`: the bubble while Java answers (`chat.ask.pending`, the patient's possessive in it);
 * - `call999`: the tel:999 link's words under an emergency answer, when Java says `call_999` (`now.call999`);
 * - `camera`: the owner's camera link (`chat.camera`).
 */
export type ComposerWords = {
  field: string;
  send: string;
  standing: string;
  hint: string;
  reply: string;
  pending: string;
  call999: string;
  camera: string;
};

/**
 * How long the composer waits for Java's answer before it shows `reply` (plan CHAT-api, Global Constraints): Java gives
 * up at its own answer deadline (45 s, both model calls together) and answers with its fixed reply, so this is that
 * deadline and the network's margin. A late answer after it is dropped (apiSend resolves `offline` at the timeout).
 */
export const ASK_TIMEOUT_MS = 50_000;

/** What Medioo's side of one exchange shows: the pending bubble, Java's answer as sent, or the fallback reply. */
type Shown = { kind: "pending" } | { kind: "answer"; answer: string; call999: boolean } | { kind: "reply" };
type Exchange = { id: number; question: string; shown: Shown };

/** Java's answer, or the reply: anything but a 200 carrying a non-empty `answer` is a failure, never an empty bubble. */
function shownFor(result: ApiResult<AskJson>): Shown {
  if (result.kind !== "ok") return { kind: "reply" };
  const answer = result.body?.answer;
  if (typeof answer !== "string" || answer.trim() === "") return { kind: "reply" };
  return { kind: "answer", answer, call999: result.body?.call_999 === true };
}

/**
 * The composer (plan W5, asking since plan CHAT-web, PO ruling P7, DR-50). A press with a non-blank line shows it as
 * the family's bubble, then Medioo's pending bubble, and posts `{question}` once to Java's
 * `POST /app/api/patients/{id}/ask`; send stays disabled, and a second press or an Enter is refused, until that one is
 * answered. Java's `answer` is shown exactly as sent, as text (React escapes it; never markup): Java has already
 * checked it in code and appended the doctor line, the urgency line, the disclaimer and the red-flag line, so nothing
 * is added or taken away here. An emergency answer offers tel:999 only when Java says `call_999`. A refusal, a dead
 * network or no answer in ASK_TIMEOUT_MS shows `reply` instead; a 401 goes to sign-in (apiSend's rule).
 *
 * The typed text is kept nowhere but this island's memory and the one request: no storage, nothing in the address. The
 * field, which paints --fill and draws --boundary, appears only once JavaScript runs: without it the standing line says
 * how to ask, and there is no form that could post the typed text anywhere (DR-42 item 1). The camera, for the owner
 * only, opens the add sheet for this patient.
 *
 * Two elements, in this order: the exchanges (`.composer__lines`, the live region, empty until the first send) in the
 * conversation's column, then the bar itself (`.composer`), which the chat page docks.
 */
export function Composer({
  words,
  owner,
  patientId,
  preview,
}: {
  words: ComposerWords;
  owner: boolean;
  patientId: string;
  preview: boolean;
}) {
  const [ready, setReady] = useState(false);
  const [text, setText] = useState("");
  const [exchanges, setExchanges] = useState<Exchange[]>([]);
  const [asking, setAsking] = useState(false);
  // The press guard, read synchronously: two submits in one tick (a double tap, a press and an Enter) see the first.
  const inFlight = useRef(false);
  const nextId = useRef(0);
  useEffect(() => setReady(true), []);

  async function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = text.trim();
    if (question === "" || inFlight.current) return;
    inFlight.current = true;
    setAsking(true);
    const id = nextId.current++;
    setExchanges((previous) => [...previous, { id, question, shown: { kind: "pending" } }]);
    setText("");
    const result = await apiSend<AskJson>(
      "POST",
      `/app/api/patients/${encodeURIComponent(patientId)}/ask`,
      { question },
      { timeoutMs: ASK_TIMEOUT_MS },
    );
    const shown = shownFor(result);
    // Each exchange is answered once: only a pending bubble is replaced.
    setExchanges((previous) => previous.map((e) => (e.id === id && e.shown.kind === "pending" ? { ...e, shown } : e)));
    inFlight.current = false;
    setAsking(false);
  }

  return (
    <>
      {/* The live region is on the page, empty, from the moment the field is: a region that enters the DOM already
          holding text is not reliably announced, and the answer carries the red-flag line (ToastRegion's pattern). Each
          of Medioo's bubbles (pending, answer, reply) is its own keyed node, so the answer that replaces the pending
          bubble is an addition the region announces. It is in the column after the thread, not inside the docked bar,
          so the conversation grows and the bar does not. */}
      {ready && (
        <ol className="composer__lines" aria-live="polite" aria-relevant="additions">
          {exchanges.map((e) => (
            <li key={e.id} className="composer__pair">
              <MessageBubble surface="sent">{e.question}</MessageBubble>
              {e.shown.kind === "answer" ? (
                <AskAnswer key="answer" answer={e.shown.answer} call999={e.shown.call999 ? words.call999 : null} />
              ) : (
                <MessageBubble key={e.shown.kind} surface="recv">
                  {e.shown.kind === "pending" ? words.pending : words.reply}
                </MessageBubble>
              )}
            </li>
          ))}
        </ol>
      )}
      <div className="composer">
        <div className="composer__bar">
          {owner && (
            <a
              className="composer__camera"
              href={href(`/app/add?patient=${encodeURIComponent(patientId)}`, preview)}
              aria-label={words.camera}
            >
              <svg className="composer__camera-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path d="M4 8.5h3.2l1.6-2.5h6.4l1.6 2.5H20v10H4z" />
                <circle cx="12" cy="13.2" r="3.4" />
              </svg>
            </a>
          )}
          {ready && (
            <form className="composer__form" onSubmit={ask}>
              <input
                className="composer__field"
                type="text"
                value={text}
                onChange={(event) => setText(event.target.value)}
                aria-label={words.field}
                placeholder={words.field}
                autoComplete="off"
                enterKeyHint="send"
              />
              <button type="submit" className="composer__send" disabled={asking}>
                {words.send}
              </button>
            </form>
          )}
        </div>
        <p className="composer__standing">{ready ? words.hint : words.standing}</p>
      </div>
    </>
  );
}
