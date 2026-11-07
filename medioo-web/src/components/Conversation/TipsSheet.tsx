"use client";

import { useEffect, useState } from "react";
import { SheetDialog } from "@/components/Sheet/SheetDialog";
import "@/components/Sheet/Sheet.css";

/**
 * The words of the tips chip and its sheet, resolved on the server (QuickActions): this island ships no copy.
 * `label` is the chip (`desk.quick.tips`); `title` and `items` are the add sheet's own tips (`addTips`: add.tips and
 * its three lines); `close` is the sheet's close (`sheet.close`).
 */
export type TipsWords = { label: string; title: string; close: string; items: string[] };

/**
 * "Tips for a good photo" under a retake card (plan desktop-parity Task 7, P19: an action, never a question to the
 * model): once the page runs, a button that opens the add sheet's tips in a sheet over the conversation. Before that,
 * and without JavaScript, a link to the add page (`fallback`, the same page the resend chip opens), where the same tips
 * are printed, so the chip is never a control that does nothing.
 */
export function TipsSheet({ words, fallback }: { words: TipsWords; fallback: string }) {
  const [ready, setReady] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => setReady(true), []);
  if (!ready) {
    return (
      <a className="quick-actions__chip" href={fallback}>
        {words.label}
      </a>
    );
  }
  return (
    <>
      <button type="button" className="quick-actions__chip" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        {words.label}
      </button>
      <SheetDialog title={words.title} closeLabel={words.close} open={open} onClose={() => setOpen(false)}>
        <ul className="quick-actions__tips">
          {words.items.map((tip) => (
            <li key={tip}>{tip}</li>
          ))}
        </ul>
      </SheetDialog>
    </>
  );
}
