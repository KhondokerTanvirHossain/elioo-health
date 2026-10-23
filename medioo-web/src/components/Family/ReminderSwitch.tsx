"use client";

import { useEffect, useRef, useState } from "react";
import { apiSend } from "@/api/browser";
import { SheetDialog } from "@/components/Sheet/SheetDialog";
// SheetDialog's look lives with the server half; a client caller imports the stylesheet itself (no copy in it).
import "@/components/Sheet/Sheet.css";

/** Every word a reminder switch says, resolved by the server (family-words.ts): this island ships no copy. */
export type ReminderWords = {
  /** What is true while reminders are on (`profile.nudges.on`); null where Java's copy has no such line (the family's). */
  on: string | null;
  /** What is true once they are stopped (`profile.nudges.off`, `optout.done.family`). */
  off: string;
  /** The one control: stop them (`profile.nudges.stop`, `optout.family`). */
  stop: string;
  /** `app.err.forbidden`: Java refused the stop as VIEW_ONLY (the viewer does not own the patient). */
  refused: string;
  /** `app.err.generic`: the stop never reached Java, or Java refused it otherwise. */
  failed: string;
  /**
   * The question asked before anything is sent, as Java's OptOutPages asks it: `optout.q` as the sheet's title, the
   * stop itself (`optout.patient`, `optout.family`) to confirm, `optout.keep` to keep them, `sheet.close`.
   */
  confirm: { title: string; yes: string; keep: string; close: string };
};

/**
 * A reminder stop (plan W10, B5): `PUT <path>` with `{"on": false}` and nothing else. Reminders can only be stopped
 * from the web until E1/E2-API (Q5, Q9; Java answers `{"on": true}` 400), so the stop is asked first, in a sheet, and
 * sent only on its confirm: keep, close and Escape send nothing (W10 review: one stray tap must not end every reminder
 * with no way back here). Once stopped this says so and offers no control at all: never a switch that looks as if it
 * could be turned back on. `canStop` false (a share member's view) shows the line and no control. `reloadAfter`: the
 * family's stop changes every patient's line, so the page is read again from Java rather than guessed here. A 401 goes
 * to sign-in holding the page (apiSend).
 *
 * For a screen reader: the status region is mounted empty and written into after a send, so a refusal is announced
 * (ToastRegion's rule); after a stop the button is gone, so the focus moves to the line that says what is true now.
 */
export function ReminderSwitch({
  path,
  stopped,
  canStop,
  words,
  reloadAfter,
}: {
  path: string;
  stopped: boolean;
  canStop: boolean;
  words: ReminderWords;
  reloadAfter?: boolean;
}) {
  const [off, setOff] = useState(stopped);
  const [asking, setAsking] = useState(false);
  const [sending, setSending] = useState(false);
  const [said, setSaid] = useState<string | null>(null);
  const [moveFocus, setMoveFocus] = useState(false);
  const line = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (moveFocus && off) line.current?.focus();
  }, [moveFocus, off]);

  async function stop() {
    setAsking(false);
    if (sending) return;
    setSending(true);
    setSaid(null);
    const result = await apiSend("PUT", path, { on: false });
    if (result.kind === "signed-out") return;
    setSending(false);
    if (result.kind === "ok") {
      setOff(true);
      setMoveFocus(true);
      if (reloadAfter) location.reload();
      return;
    }
    setSaid(result.kind === "refused" && result.reason === "VIEW_ONLY" ? words.refused : words.failed);
  }

  const text = off ? words.off : words.on;
  return (
    <div className="family-reminders" data-reminders={off ? "stopped" : "on"}>
      {text !== null && (
        <p className="family-reminders__line" ref={line} tabIndex={-1}>
          {text}
        </p>
      )}
      {!off && canStop && (
        <>
          <button
            type="button"
            className="family-reminders__stop"
            aria-disabled={sending}
            aria-haspopup="dialog"
            onClick={() => {
              if (!sending) setAsking(true);
            }}
          >
            {words.stop}
          </button>
          <SheetDialog title={words.confirm.title} closeLabel={words.confirm.close} open={asking} onClose={() => setAsking(false)}>
            <div className="family-reminders__confirm">
              <button type="button" className="family-reminders__yes" onClick={stop}>
                {words.confirm.yes}
              </button>
              <button type="button" className="family-reminders__keep" onClick={() => setAsking(false)}>
                {words.confirm.keep}
              </button>
            </div>
          </SheetDialog>
        </>
      )}
      <p className="family-reminders__status" role="status" aria-live="polite" aria-atomic="true">
        {said}
      </p>
    </div>
  );
}
