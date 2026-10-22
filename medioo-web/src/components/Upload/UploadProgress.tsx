import { useId } from "react";
import type { UploadWords } from "@/app/app/(signed-in)/add/upload-words";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { fillSlots } from "./slots";

/** Where a press is: on its way (and slow, when nothing has moved for a while), stuck on the phone, or arrived. */
export type UploadPhase =
  | { kind: "sending"; sent: number; total: number; slow: boolean }
  | { kind: "stuck" }
  | { kind: "sent" };

/**
 * The upload step (s09), as the family's own message on its way: the patient's title, how many pages of the press have
 * gone (counted on the bytes the browser reports sent, against each page's own size), the bar, and the lines the HTML
 * sheet says: slow (no page is lost), stuck (kept on the phone) with the one retry, sent. Never red and never amber: a
 * slow network is not an urgency. The retry sends the same press again; the sheet owns which key that is.
 */
export function UploadProgress({
  phase,
  title,
  words,
  digits,
  sizes,
  onRetry,
}: {
  phase: UploadPhase;
  title: string;
  words: UploadWords["up"];
  digits: string;
  /** Each page's size in bytes, in the order sent. */
  sizes: number[];
  onRetry: () => void;
}) {
  const titleId = useId();
  const total = sizes.length;
  const all = sizes.reduce((a, b) => a + b, 0);
  // The pages wholly gone: the body's bytes sent, measured against the pages' own bytes (the form's few header bytes
  // are not a page), so "3 of 3" is said only once the last page's bytes have left.
  const sentBytes = phase.kind === "sending" && phase.total > 0 ? (phase.sent / phase.total) * all : phase.kind === "sent" ? all : 0;
  let done = 0;
  for (let i = 0, edge = 0; i < sizes.length; i++) {
    edge += sizes[i]!;
    if (sentBytes >= edge) done = i + 1;
  }
  const line = phase.kind === "sent" ? words.sent : phase.kind === "stuck" ? words.stuck : fillSlots(words.count, { n: done, total }, digits);
  return (
    <section className="upload-progress" aria-labelledby={titleId} aria-live="polite" data-phase={phase.kind}>
      <h2 className="upload-progress__title" id={titleId} tabIndex={-1}>
        {title}
      </h2>
      <MessageBubble surface="sent" tail>
        <p className="upload-progress__line">{line}</p>
        <progress className="upload-progress__bar" max={1} value={all === 0 ? 0 : sentBytes / all} aria-label={line} />
      </MessageBubble>
      {phase.kind === "sending" && phase.slow && <p className="upload-progress__note">{words.slow}</p>}
      {phase.kind === "stuck" && (
        <>
          <p className="upload-progress__note">{words.slow}</p>
          <button type="button" className="upload-progress__retry" data-primary="" onClick={onRetry}>
            {words.retry}
          </button>
        </>
      )}
      <p className="upload-progress__hint">{words.after}</p>
      <p className="upload-progress__hint">{words.keep}</p>
    </section>
  );
}
