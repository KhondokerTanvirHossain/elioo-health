"use client";

import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { apiSend, type ApiResult } from "@/api/browser";
import type { AskJson, AskMedicineJson } from "@/api/types";
import type { CropWords } from "@/components/Crop/Crop";
import { AskAnswer } from "@/components/safety/AskAnswer/AskAnswer";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { href, signInPath } from "@/routing/paths";
import { ASK_TIMEOUT_MS } from "./ask-limits";
import "./Composer.css";

/**
 * Every word the composer says, resolved on the server (home/chat-words.ts): this island ships no copy.
 * - `field`: the field's name (`chat.field`);
 * - `send`: the send button (`add.send`);
 * - `hint`: the line under the bar (`chat.composer.hint`: answers come only from the papers). Without JavaScript the
 *   server parent adds, inside a `<noscript>`, the line that says how to ask (`nojs`, below);
 * - `reply`: what Medioo says when no answer came (a refusal, a dead network, the timeout, a session that ended):
 *   `chat.ask.reply`, then `safety.red_flag_line` appended by code, as WhatsApp appends it to `wa.fallback`;
 * - `tooLong`: Java refused the question as over its length (`chat.composer.too_long` with the limit), then the
 *   red-flag line;
 * - `signIn`: the sign-in link beside the reply when the session ended (`login.enter`);
 * - `pending`: the bubble while Java answers (`chat.composer.pending`, the patient's possessive in it);
 * - `call999`: the tel:999 link's words under an emergency answer, when Java says `call_999` (`now.call999`);
 * - `crop`: the crop's words, for the crop of a prescription line an answer quotes (`health.crop.alt`, `crop.retry`);
 * - `camera`: the owner's camera link (`chat.camera`).
 */
export type ComposerWords = {
  field: string;
  send: string;
  hint: string;
  reply: string;
  tooLong: string;
  signIn: string;
  pending: string;
  call999: string;
  crop: CropWords;
  camera: string;
};


/** What Medioo's side of one exchange shows: the pending bubble, Java's answer as sent, or a fixed line of its own. */
type Shown =
  | { kind: "pending" }
  | { kind: "answer"; answer: string; call999: boolean; medicines: AskMedicineJson[] }
  | { kind: "reply" }
  | { kind: "too-long" }
  | { kind: "signed-out" };
type Exchange = { id: number; question: string; turnId: string | null; shown: Shown };

/**
 * Java's answer, or a fixed line: anything but a 200 carrying a non-empty `answer` is a failure, never an empty bubble.
 * A 400 is Java's refusal of the question itself (the field sends only a non-blank question, so its length); a 401 is
 * the session that ended, which keeps the reply on screen with a sign-in link.
 */
function shownFor(result: ApiResult<AskJson>): { shown: Shown; turnId: string | null } {
  if (result.kind === "signed-out") return { shown: { kind: "signed-out" }, turnId: null };
  if (result.kind === "refused" && result.status === 400) return { shown: { kind: "too-long" }, turnId: null };
  if (result.kind !== "ok") return { shown: { kind: "reply" }, turnId: null };
  const answer = result.body?.answer;
  if (typeof answer !== "string" || answer.trim() === "") return { shown: { kind: "reply" }, turnId: null };
  const medicines = Array.isArray(result.body?.medicines) ? result.body.medicines : [];
  const turnId = typeof result.body?.turn_id === "string" ? result.body.turn_id : null;
  return { shown: { kind: "answer", answer, call999: result.body?.call_999 === true, medicines }, turnId };
}

/**
 * After an exchange is drawn, where the window goes (the docked composer keeps its own place just after the newest
 * exchange, home.css). The pending bubble, and an answer that fits in the space between the toolbar and the composer:
 * the page's end, so the whole exchange, an emergency answer and its 999 call above all, is in view and never under the
 * bar. An answer taller than that space (Java allows 1200 characters): the sent question's top at the toolbar's foot,
 * so the family reads the answer from its first line, not from the disclaimer at its end.
 */
function bringIntoView(pair: HTMLElement | null, bar: HTMLElement | null, answered: boolean) {
  const end = () => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: "instant" });
  if (!answered || pair === null || bar === null) return end();
  const toolbar = document.querySelector(".app-shell__toolbar")?.getBoundingClientRect().bottom ?? 0;
  const room = bar.getBoundingClientRect().top - Math.max(0, toolbar);
  const box = pair.getBoundingClientRect();
  if (box.height <= room) return end();
  window.scrollTo({ top: window.scrollY + box.top - Math.max(0, toolbar), behavior: "instant" });
}

/**
 * The composer (plan W5, asking since plan CHAT-web, PO ruling P7, DR-50). A press with a non-blank line shows it as
 * the family's bubble, then Medioo's pending bubble, and posts `{question}` once to Java's
 * `POST /app/api/patients/{id}/ask`; a second press or an Enter is refused until that one is answered. Send is marked
 * `aria-disabled` meanwhile, never `disabled`: a disabled button drops the keyboard's focus to the page (WCAG 2.4.3).
 * Java's `answer` is shown exactly as sent, as text (React escapes it; never markup): Java has already checked it in
 * code and appended the doctor line, the urgency line, the disclaimer and the red-flag line, so nothing is added or
 * taken away here; the crop of each prescription line it quotes is drawn before it. An emergency answer offers tel:999
 * only when Java says `call_999`. A refusal, a dead network or no answer in ASK_TIMEOUT_MS shows `reply` instead;
 * a question Java refuses as too long shows `tooLong`; a session that ended keeps `reply` (with its red-flag line) on
 * screen beside a sign-in link, and does not leave the page by itself.
 *
 * `answered`: the turn ids the server-rendered thread already draws (its asks). An exchange of this island whose turn
 * is among them is left out, so a server re-render without a remount (a router refresh, the chat tab pressed on this
 * page) never draws the same question and answer twice.
 *
 * The typed text is kept nowhere but this island's memory and the one request: no storage, nothing in the address. The
 * field, which paints --fill and draws --boundary, appears only once JavaScript runs; without it there is no form that
 * could post the typed text anywhere (DR-42 item 1), and `nojs`, the server parent's `<noscript>` line, says how to
 * ask. The camera, for the owner only, opens the add sheet for this patient.
 *
 * Two elements, in this order: the exchanges (`.composer__lines`, the live region, empty until the first send) in the
 * conversation's column, then the bar itself (`.composer`), which the chat page docks.
 */
export function Composer({
  words,
  owner,
  patientId,
  preview,
  answered = [],
  nojs,
}: {
  words: ComposerWords;
  owner: boolean;
  patientId: string;
  preview: boolean;
  answered?: readonly string[];
  nojs?: ReactNode;
}) {
  const [ready, setReady] = useState(false);
  const [text, setText] = useState("");
  const [exchanges, setExchanges] = useState<Exchange[]>([]);
  const [asking, setAsking] = useState(false);
  // The press guard, read synchronously: two submits in one tick (a double tap, a press and an Enter) see the first.
  const inFlight = useRef(false);
  const nextId = useRef(0);
  const lines = useRef<HTMLOListElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  useEffect(() => setReady(true), []);
  const shown = exchanges.filter((e) => e.turnId === null || !answered.includes(e.turnId));
  // Only on a change the family's own press made: nothing moves on mount, where OpenAtNewest decides.
  useEffect(() => {
    if (exchanges.length === 0) return;
    const newest = exchanges[exchanges.length - 1]!;
    bringIntoView(lines.current?.lastElementChild as HTMLElement | null, bar.current, newest.shown.kind === "answer");
  }, [exchanges]);

  async function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = text.trim();
    if (question === "" || inFlight.current) return;
    inFlight.current = true;
    setAsking(true);
    const id = nextId.current++;
    setExchanges((previous) => [...previous, { id, question, turnId: null, shown: { kind: "pending" } }]);
    setText("");
    const result = await apiSend<AskJson>(
      "POST",
      `/app/api/patients/${encodeURIComponent(patientId)}/ask`,
      { question },
      { timeoutMs: ASK_TIMEOUT_MS, stayOnSignOut: true },
    );
    const { shown: next, turnId } = shownFor(result);
    // Each exchange is answered once: only a pending bubble is replaced.
    setExchanges((previous) => previous.map((e) => (e.id === id && e.shown.kind === "pending" ? { ...e, turnId, shown: next } : e)));
    inFlight.current = false;
    setAsking(false);
  }

  function medioo(e: Exchange) {
    switch (e.shown.kind) {
      case "answer":
        return (
          <AskAnswer
            key="answer"
            answer={e.shown.answer}
            call999={e.shown.call999 ? words.call999 : null}
            medicines={e.shown.medicines}
            crop={words.crop}
          />
        );
      case "signed-out":
        return (
          <div key="signed-out" className="composer__signed-out">
            <MessageBubble surface="recv">{words.reply}</MessageBubble>
            <a className="composer__signin" href={signInPath(location.pathname + location.search)}>
              {words.signIn}
            </a>
          </div>
        );
      default:
        return (
          <MessageBubble key={e.shown.kind} surface="recv">
            {e.shown.kind === "pending" ? words.pending : e.shown.kind === "too-long" ? words.tooLong : words.reply}
          </MessageBubble>
        );
    }
  }

  return (
    <>
      {/* The live region is on the page, empty, from the moment the field is: a region that enters the DOM already
          holding text is not reliably announced, and the answer carries the red-flag line (ToastRegion's pattern). Each
          of Medioo's bubbles (pending, answer, reply) is its own keyed node, so the answer that replaces the pending
          bubble is an addition the region announces. It is in the column after the thread, not inside the docked bar,
          so the conversation grows and the bar does not. */}
      {ready && (
        <ol ref={lines} className="composer__lines" aria-live="polite" aria-relevant="additions">
          {shown.map((e) => (
            <li key={e.id} className="composer__pair">
              <MessageBubble surface="sent">{e.question}</MessageBubble>
              {medioo(e)}
            </li>
          ))}
        </ol>
      )}
      <div ref={bar} className="composer">
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
              <button type="submit" className="composer__send" aria-disabled={asking || undefined}>
                {words.send}
              </button>
            </form>
          )}
        </div>
        <p className="composer__standing">{words.hint}</p>
        {nojs}
      </div>
    </>
  );
}
