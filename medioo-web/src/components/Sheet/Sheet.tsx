import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import { SheetDialog } from "./SheetDialog";
import "./Sheet.css";

/**
 * The server convenience for a sheet: it resolves the words with `t()` (the title, from a copy key, is the dialog's
 * accessible name, and the close button's label) and hands them to the client half, so a sheet rendered from a server
 * component ships its behaviour to the browser and not the copy. No inline style: the look is Sheet.css.
 *
 * It takes no function prop, on purpose: a server component cannot pass one to a client component, and a client
 * component that imported this file would pull `@/copy` (all of bn and en) into the browser bundle. A caller that
 * opens and closes the sheet (and needs `onClose`) is a client component: it renders `SheetDialog` directly, with
 * `title` and `closeLabel` resolved by its server parent and passed down as props. Sheet.test.tsx holds this at the
 * type level (`sheetTakesNoFunctionProp`), eslint refuses this file and `@/copy` in a `"use client"` file, and
 * built/client-copy.built.test.ts checks that no client chunk of either build carries copy.
 */
export function Sheet({
  titleKey,
  open,
  children,
  lang,
}: {
  titleKey: CopyKey;
  open: boolean;
  children: ReactNode;
  lang: Lang;
}) {
  return (
    <SheetDialog title={t(lang, titleKey)} closeLabel={t(lang, "sheet.close")} open={open}>
      {children}
    </SheetDialog>
  );
}
