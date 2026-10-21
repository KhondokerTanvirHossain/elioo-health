"use client";

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";

/**
 * Sheet's client half: the modal `<dialog>` and the little it needs a browser for. It takes its words already resolved
 * (Sheet passes them from the copy), so the copy never ships to the browser for a sheet's sake.
 */
export function SheetDialog({
  title,
  closeLabel,
  open,
  children,
  onClose,
}: {
  title: string;
  closeLabel: string;
  open: boolean;
  children: ReactNode;
  onClose?: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || !onClose) return;
    dialog.addEventListener("close", onClose);
    return () => dialog.removeEventListener("close", onClose);
  }, [onClose]);

  // Escape is handled here, not left to the platform's cancel, so every browser closes the same way and `close` fires once.
  const onKeyDown = (e: KeyboardEvent<HTMLDialogElement>) => {
    if (e.key !== "Escape") return;
    e.preventDefault();
    ref.current?.close();
  };

  return (
    <dialog ref={ref} className="sheet" aria-labelledby={titleId} onKeyDown={onKeyDown}>
      <header className="sheet__head">
        <h2 className="sheet__title" id={titleId}>
          {title}
        </h2>
        <button type="button" className="sheet__close" onClick={() => ref.current?.close()}>
          {closeLabel}
        </button>
      </header>
      <div className="sheet__body">{children}</div>
    </dialog>
  );
}
