"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, type ComponentProps } from "react";
import { UploadSheet } from "@/components/Upload/UploadSheet";
import "./DeskUpload.css";

/**
 * The add sheet as desktop.html's dialog (`uploadDlg`, `.dlg`), over the talk-and-papers screen at `?add=1` (plan
 * desktop-parity U1): today's UploadSheet, every rule of it kept (whose paper, one report or separate, the tray and its
 * cap, the kinds Java reads, a press's key and retries, Java's refusals), in a modal dialog. The server draws it open, so
 * without JavaScript it is the sheet as drawn, every control off (DR-42); with JavaScript it becomes modal (the page
 * behind is inert), and Escape goes where the sheet's own close goes, the same screen without the dialog. `label` is the
 * sheet's heading (add.title), the dialog's accessible name. Loaded by its loader only where the dialog renders.
 */
export default function DeskUploadDialog({ label, ...sheet }: { label: string } & ComponentProps<typeof UploadSheet>) {
  const ref = useRef<HTMLDialogElement>(null);
  const router = useRouter();
  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null || typeof dialog.showModal !== "function") return;
    if (dialog.open) dialog.close();
    dialog.showModal();
  }, []);
  return (
    <dialog
      ref={ref}
      className="desk-upload"
      aria-label={label}
      open
      onCancel={(e) => {
        e.preventDefault();
        router.replace(sheet.closeHref, { scroll: false });
      }}
    >
      <UploadSheet {...sheet} />
    </dialog>
  );
}
