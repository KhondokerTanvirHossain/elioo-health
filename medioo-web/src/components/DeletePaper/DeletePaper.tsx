"use client";

import { useEffect, useState } from "react";
import { apiGet, apiSend } from "@/api/browser";
import type { DeleteReportJson, DeleteTokenJson } from "@/api/types";
import { SheetDialog } from "@/components/Sheet/SheetDialog";
import { ToastRegion } from "@/components/Toast/ToastRegion";
import "@/components/Sheet/Sheet.css";
import "@/components/Toast/Toast.css";
import "./DeletePaper.css";

/** Every word the delete says, resolved by its server parent. */
export type DeleteWords = {
  /** `detail.delete`: the button that asks. */
  open: string;
  /** `doc.delete.q`, the sheet's title. */
  question: string;
  /** `doc.delete.warn`. */
  warn: string;
  /** `doc.delete.yes`. */
  yes: string;
  /** `doc.delete.no`, which is also the sheet's close. */
  no: string;
  /** `doc.gone`: another member deleted it first. */
  gone: string;
  /** `app.err.forbidden`: a share member's delete (VIEW_ONLY). */
  refused: string;
  /** `app.err.generic`: anything else, the connection included. */
  failed: string;
};

/**
 * Deleting a paper (plan W6; B4c's two deliberate steps), for the paper's owner only: the server parent renders this
 * only when `owner` is true, and Java refuses a share member's delete anyway (403 VIEW_ONLY, said as `refused`). The
 * button asks in a SheetDialog (`question`, `warn`); `yes` fetches a fresh confirm token for this paper in this
 * session and sends it with the DELETE, so a token that expired or was refused (400 CONFIRM_REQUIRED) is simply
 * replaced on the next yes; that refusal is said inside the open sheet, since a toast outside a modal is inert. A
 * deleted paper reloads its own address with `?deleted=1`, which Java now answers 404, and the page says it is gone.
 * A delete raced by another member (404) says `gone`. It needs JavaScript, so without it nothing is rendered (DR-42:
 * every mutation needs JavaScript and is never a form that posts somewhere).
 */
export function DeletePaper({ documentId, words }: { documentId: string; words: DeleteWords }) {
  const [mounted, setMounted] = useState(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // What is said while the sheet stays open: showModal makes everything outside the dialog inert (the toast included),
  // so a failure the family must see before pressing again is said inside it.
  const [inSheet, setInSheet] = useState("");
  useEffect(() => setMounted(true), []);
  if (!mounted) return null;

  const say = (text: string) => {
    setOpen(false);
    setMessage(text);
  };

  const confirm = async () => {
    setBusy(true);
    setInSheet("");
    try {
      const token = await apiGet<DeleteTokenJson>(`/app/api/documents/${documentId}/delete-token`);
      if (token.kind === "signed-out") return;
      if (token.kind !== "ok" || token.body === null) return say(reasonText(token));
      const deleted = await apiSend<DeleteReportJson>("DELETE", `/app/api/documents/${documentId}`, {
        confirm_token: token.body.confirm_token,
      });
      if (deleted.kind === "signed-out") return;
      if (deleted.kind === "ok") {
        const here = new URL(location.href);
        here.searchParams.set("deleted", "1");
        location.replace(here.pathname + here.search);
        return;
      }
      // A token that was not this session's or had expired: the sheet stays, says so inside, and the next yes fetches a
      // fresh one.
      if (deleted.kind === "refused" && deleted.reason === "CONFIRM_REQUIRED") return setInSheet(words.failed);
      say(reasonText(deleted));
    } finally {
      setBusy(false);
    }
  };

  function reasonText(answer: { kind: string; status?: number; reason?: string }): string {
    if (answer.kind === "refused" && answer.status === 404) return words.gone;
    if (answer.kind === "refused" && answer.reason === "VIEW_ONLY") return words.refused;
    return words.failed;
  }

  return (
    <div className="delete-paper">
      <button
        type="button"
        className="delete-paper__open"
        onClick={() => {
          setInSheet("");
          setOpen(true);
        }}
      >
        {words.open}
      </button>
      <SheetDialog title={words.question} closeLabel={words.no} open={open} onClose={() => setOpen(false)}>
        <p className="delete-paper__warn">{words.warn}</p>
        {/* Rendered empty from the start, so what is written into it later is announced (as ToastRegion's region). */}
        <p className="delete-paper__said" role="status" aria-live="polite" aria-atomic="true">
          {inSheet}
        </p>
        <div className="delete-paper__actions">
          <button type="button" className="delete-paper__yes" disabled={busy} onClick={() => void confirm()}>
            {words.yes}
          </button>
          <button type="button" className="delete-paper__no" onClick={() => setOpen(false)}>
            {words.no}
          </button>
        </div>
      </SheetDialog>
      <ToastRegion message={message} />
    </div>
  );
}
