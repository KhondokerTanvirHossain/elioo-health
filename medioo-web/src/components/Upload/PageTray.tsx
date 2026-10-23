import type { Ref } from "react";
import type { UploadWords } from "@/app/app/(signed-in)/add/upload-words";
import { fillSlots } from "./slots";

/** One page on the tray: its file, a thumbnail URL for an image (none for a PDF), and where it came from. */
export type TrayPage = { id: string; file: File; url: string | null; src: "camera" | "gallery" | "pdf" | null };

/** A page's three moves, named on its buttons (`data-control`) so the sheet can put focus back on the one used. */
export type TrayControl = "up" | "down" | "remove";

/**
 * The page tray (s08): the pages of one press in the order they will be sent, each with its thumbnail, its number and
 * where it came from, and the three moves a page has (up, down, out). The cap is Java's (`limits.max_pages`, or
 * `max_files` for separate papers), said in the tray's own line; at the cap "more pages" is off. Every button is
 * `type="button"`: nothing here posts anything. Its words are the sheet's (upload-words.ts), slots unfilled. Each row
 * carries its page's id (`data-page-id`) and each control its move (`data-control`); the title, or the order line for
 * one page, is where focus lands when the tray opens (`data-tray-focus`), as upload.js focuses `#tray-title`.
 */
export function PageTray({
  ref,
  pages,
  words,
  digits,
  max,
  disabled,
  onMove,
  onRemove,
  onMore,
}: {
  ref?: Ref<HTMLElement>;
  pages: TrayPage[];
  words: UploadWords["tray"];
  digits: string;
  max: number;
  disabled: boolean;
  onMove: (index: number, by: -1 | 1) => void;
  onRemove: (index: number) => void;
  onMore: () => void;
}) {
  const fill = (line: string, n: number) => fillSlots(line, { n, max }, digits);
  const titled = pages.length > 1;
  return (
    <section className="upload-tray" ref={ref}>
      {titled && (
        <h2 className="upload-tray__title" tabIndex={-1} data-tray-focus="">
          {fillSlots(words.title, { n: pages.length }, digits)}
        </h2>
      )}
      <p className="upload-tray__order" tabIndex={titled ? undefined : -1} data-tray-focus={titled ? undefined : ""}>
        {words.order}
      </p>
      <ol className="upload-tray__list">
        {pages.map((p, i) => (
          <li className="upload-tray__item" key={p.id} data-page={i + 1} data-page-id={p.id}>
            {p.url === null ? (
              <span className="upload-tray__thumb upload-tray__thumb--pdf" aria-hidden="true">
                {words.src.pdf}
              </span>
            ) : (
              // A blob: URL of the family's own photo, before it is sent (CSP img-src allows blob:); no next/image.
              // eslint-disable-next-line @next/next/no-img-element
              <img className="upload-tray__thumb" src={p.url} alt="" />
            )}
            <span className="upload-tray__text">
              <strong className="upload-tray__page">{fill(words.page, i + 1)}</strong>
              {p.src !== null && <span className="upload-tray__src">{words.src[p.src]}</span>}
            </span>
            <span className="upload-tray__controls">
              <button
                type="button"
                className="upload-tray__control"
                data-control="up"
                aria-label={fill(words.up, i + 1)}
                disabled={disabled || i === 0}
                onClick={() => onMove(i, -1)}
              >
                <Chevron up />
              </button>
              <button
                type="button"
                className="upload-tray__control"
                data-control="down"
                aria-label={fill(words.down, i + 1)}
                disabled={disabled || i === pages.length - 1}
                onClick={() => onMove(i, 1)}
              >
                <Chevron />
              </button>
              <button
                type="button"
                className="upload-tray__control"
                data-control="remove"
                aria-label={fill(words.remove, i + 1)}
                disabled={disabled}
                onClick={() => onRemove(i)}
              >
                <svg className="upload-tray__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </span>
          </li>
        ))}
      </ol>
      <button type="button" className="upload-tray__more" disabled={disabled || pages.length >= max} onClick={onMore}>
        <svg className="upload-tray__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M12 5v14M5 12h14" />
        </svg>
        <span>{words.more}</span>
      </button>
      <p className="upload-tray__limit">{fill(words.limit, pages.length)}</p>
    </section>
  );
}

function Chevron({ up }: { up?: boolean }) {
  return (
    <svg className="upload-tray__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={up ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6"} />
    </svg>
  );
}
