"use client";

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";

/**
 * The sheet a client component opens and closes: the modal `<dialog>` and the little it needs a browser for. This is
 * the API for any caller that owns `open` (the upload sheet, W7): it takes its words already resolved, `title` and
 * `closeLabel`, by a server parent that passes them down as props, so the copy never ships to the browser for a
 * sheet's sake. A `"use client"` file may not import `@/copy` or `Sheet.tsx` (eslint, and the built-chunk scan in
 * built/client-copy.built.test.ts); `Sheet` is the server convenience that resolves the words for a server caller.
 *
 * `showModal()` puts it in the top layer, makes the rest of the page inert and traps focus, so none of that is
 * re-implemented here. Escape and the close button close it, and `onClose` tells the caller (whose `open` would
 * otherwise still say true). An Escape that a child already handled (`preventDefault`), or one that ends an IME
 * composition (Bangla typed through Avro or a phone keyboard), does not close it; and a sheet inside another sheet
 * closes alone, as the platform closes only the topmost modal.
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

  // Escape is handled here, not left to the platform's cancel, so every browser closes the same way and `close` fires
  // once. preventDefault marks the Escape as used, so an outer sheet's handler (the same keydown bubbling on) leaves it.
  const onKeyDown = (e: KeyboardEvent<HTMLDialogElement>) => {
    if (e.key !== "Escape" || e.defaultPrevented || e.nativeEvent.isComposing) return;
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
