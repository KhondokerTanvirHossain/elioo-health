"use client";

import { useEffect, useState, type FormEvent } from "react";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { href } from "@/routing/paths";
import "./Composer.css";

/**
 * Every word the composer says, resolved on the server (home/chat-words.ts): this island ships no copy.
 * - `field`: the field's name (`chat.field`);
 * - `send`: the send button (`add.send`);
 * - `standing`: the line under the field (`chat.soon`), as Java's ChatView shows it;
 * - `reply`: what Medioo answers to a typed line until D11 (`chat.ask.reply`, then `safety.red_flag_line` appended by
 *   code, as WhatsApp appends it to `wa.fallback`);
 * - `camera`: the owner's camera link (`chat.camera`).
 */
export type ComposerWords = { field: string; send: string; standing: string; reply: string; camera: string };

/**
 * The composer (plan W5), honest until D11 signs chat answers: a typed line is shown back as the family's own bubble and
 * answered with the reply WhatsApp gives (the decline, "ask a doctor", the red-flag line). It is sent nowhere and kept
 * nowhere: no request, no storage, nothing in the address; the lines live in this island's memory until the page
 * goes. The field, which paints --fill and draws --boundary, appears only once JavaScript runs: without it the
 * standing line says asking is coming, and there is no form that could post the typed text anywhere (DR-42 item 1).
 * The camera, for the owner only, opens the add sheet for this patient (Java's /app/add until W7).
 *
 * Two elements, in this order: the typed lines and their replies (`.composer__lines`, the live region, empty until the
 * first send) in the conversation's column, then the bar itself (`.composer`), which the chat page docks.
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
  const [lines, setLines] = useState<string[]>([]);
  useEffect(() => setReady(true), []);

  function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const line = text.trim();
    if (line === "") return;
    setLines((previous) => [...previous, line]);
    setText("");
  }

  return (
    <>
      {/* The live region is on the page, empty, from the moment the field is: a region that enters the DOM already
          holding text is not reliably announced, and the reply carries the red-flag line (ToastRegion's pattern). It
          is in the column after the thread, not inside the docked bar, so the conversation grows and the bar does not. */}
      {ready && (
        <ol className="composer__lines" aria-live="polite" aria-relevant="additions">
          {lines.map((line, k) => (
            <li key={k} className="composer__pair">
              <MessageBubble surface="sent">{line}</MessageBubble>
              <MessageBubble surface="recv">{words.reply}</MessageBubble>
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
              <button type="submit" className="composer__send">
                {words.send}
              </button>
            </form>
          )}
        </div>
        <p className="composer__standing">{words.standing}</p>
      </div>
    </>
  );
}
