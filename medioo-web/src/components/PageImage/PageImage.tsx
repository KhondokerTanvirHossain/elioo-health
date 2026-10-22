"use client";

import { useState, type MouseEvent } from "react";
import { SheetDialog } from "@/components/Sheet/SheetDialog";
import "@/components/Sheet/Sheet.css";
import "./PageImage.css";

/** What a page link says, resolved by its server parent: the link's own words, the sheet's title and its close button. */
export type ZoomWords = { zoom: string; title: string; closeLabel: string };

/**
 * One whole page of the paper (plan W6): a link to the page image Java serves, which opens it in the browser, where it
 * pinch-zooms, and works without JavaScript. With JavaScript the same press opens the page in a SheetDialog instead,
 * so the family stays on the paper. The image is loaded only when the sheet opens.
 */
export function PageImage({ src, words }: { src: string; words: ZoomWords }) {
  const [open, setOpen] = useState(false);
  const zoom = (e: MouseEvent<HTMLAnchorElement>) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    setOpen(true);
  };
  return (
    <>
      <a className="page-image__open" href={src} onClick={zoom}>
        <svg className="page-image__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M6 3.5h8.5L19 8v12.5H6zM14.5 3.5V8H19M10.5 12a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM12.3 16.3l2.2 2.2" />
        </svg>
        <span className="page-image__label">{words.zoom}</span>
      </a>
      <SheetDialog title={words.title} closeLabel={words.closeLabel} open={open} onClose={() => setOpen(false)}>
        {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
        {open && <img className="page-image__img" src={src} alt={words.title} />}
      </SheetDialog>
    </>
  );
}
