import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import { SheetDialog } from "./SheetDialog";
import "./Sheet.css";

/**
 * A sheet over the page, on the platform's modal `<dialog>`: `showModal()` puts it in the top layer, makes the rest of
 * the page inert and traps focus, so none of that is re-implemented here. `open` opens and closes it; Escape and the
 * close button close it, and `onClose` tells the caller (whose `open` would otherwise still say true). The title,
 * from a copy key, is the dialog's accessible name (`aria-labelledby`). No inline style: the look is Sheet.css.
 *
 * The words are resolved here and handed to the client half (SheetDialog), so rendered from a server component the
 * sheet ships its behaviour to the browser and not the copy.
 */
export function Sheet({
  titleKey,
  open,
  children,
  lang,
  onClose,
}: {
  titleKey: CopyKey;
  open: boolean;
  children: ReactNode;
  lang: Lang;
  onClose?: () => void;
}) {
  return (
    <SheetDialog title={t(lang, titleKey)} closeLabel={t(lang, "sheet.close")} open={open} onClose={onClose}>
      {children}
    </SheetDialog>
  );
}
