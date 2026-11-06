"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

/** What the zoom says, resolved by its server parent: each button's name, and the ten digits the percentage is written in. */
export type ViewerZoomWords = { out: string; in: string; fit: string; digits: string };

/** desktop.html's zoom steps: 50% to 300% by 25%. */
export const ZOOM_MIN = 0.5;
export const ZOOM_MAX = 3;
export const ZOOM_STEP = 0.25;
/** The page's width at 100% (desktop.html's `.page`, 640 px): what a percentage is a percentage of. */
const PAGE_WIDTH = 640;

/** `fit`: the page as wide as the canvas. A number: one of the steps. */
export type ZoomLevel = number | "fit";

const clamp = (z: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z));

/** The next step up or down from `from` (which may lie between steps, after a fit), held inside 50%–300%. */
export function stepZoom(from: number, by: 1 | -1): number {
  const steps = from / ZOOM_STEP;
  const next = by > 0 ? Math.floor(steps + 1e-9) + 1 : Math.ceil(steps - 1e-9) - 1;
  return clamp(next * ZOOM_STEP);
}

/** "125%" in the given digits (the page's: Bangla on bn), from ASCII. */
function written(n: number, digits: string): string {
  return `${Math.round(n * 100)}%`.replace(/[0-9]/g, (d) => digits[Number(d)] ?? d);
}

/** Whether a key press belongs to a field the family is typing in (the composer), never to the viewer. */
function typing(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return target.closest("input, textarea, select, [contenteditable=''], [contenteditable='true']") !== null;
}

const Icon = ({ d }: { d: string }) => (
  <svg className="paper-viewer__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path d={d} />
  </svg>
);

/**
 * The viewer's zoom and keys (plan desktop-parity Task 6; desktop.html's zout / zin / zfit and its keydown), a client
 * island around the server-rendered toolbar parts and pages:
 * - − / the percentage / + / fit, between `start` (prev, next, the title, the urgency) and `end` (delete); steps of
 *   25% from 50% to 300%, held at either end; fit makes the page as wide as the canvas, and is where it opens;
 * - the keys: `-` smaller, `+` (or `=`) larger, `0` fit; ← the paper before, → the paper after (the toolbar's own
 *   links, followed as a click). Never while the family types in a field, with a modifier held (Ctrl - is the
 *   browser's), with a dialog open, or below 900 px, where the phone page is the one showing.
 * The level is a `data-zoom` attribute the CSS sizes the pages by: no inline style (style-src 'self').
 */
export function Zoom({
  words,
  start,
  end,
  canvasLabel,
  children,
}: {
  words: ViewerZoomWords;
  start: ReactNode;
  end: ReactNode;
  canvasLabel: string;
  children: ReactNode;
}) {
  const [level, setLevel] = useState<ZoomLevel>("fit");
  // What fit came to on this screen, measured; null until measured (and wherever nothing is laid out).
  const [fitted, setFitted] = useState<number | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLDivElement>(null);

  const current = () => (level === "fit" ? (fitted ?? 1) : level);
  const by = (dir: 1 | -1) => setLevel(stepZoom(current(), dir));

  useEffect(() => {
    if (level !== "fit") return;
    const measure = () => {
      const page = canvas.current?.querySelector("img");
      const width = page?.getBoundingClientRect().width ?? 0;
      setFitted(width > 0 ? width / PAGE_WIDTH : null);
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [level]);

  // The handler reads the latest level through a ref, so the listener is added once.
  const latest = useRef({ current, by });
  useEffect(() => {
    latest.current = { current, by };
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey || typing(e.target)) return;
      if (document.querySelector("dialog[open]") !== null) return;
      if (typeof window.matchMedia === "function" && window.matchMedia("(max-width: 899px)").matches) return;
      if (e.key === "-") latest.current.by(-1);
      else if (e.key === "+" || e.key === "=") latest.current.by(1);
      else if (e.key === "0") setLevel("fit");
      else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        const link = root.current?.querySelector<HTMLAnchorElement>(`a[data-step="${e.key === "ArrowLeft" ? "prev" : "next"}"][href]`);
        if (!link) return;
        e.preventDefault();
        link.click();
        return;
      } else return;
      e.preventDefault();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const shown = current();
  const atMin = level !== "fit" && level <= ZOOM_MIN;
  const atMax = level !== "fit" && level >= ZOOM_MAX;
  return (
    <div className="paper-viewer__body" ref={root}>
      <div className="paper-viewer__toolbar">
        {start}
        <span className="paper-viewer__sep" aria-hidden="true" />
        <button
          type="button"
          className="paper-viewer__button"
          aria-label={words.out}
          title={words.out}
          aria-disabled={atMin || undefined}
          onClick={() => by(-1)}
        >
          <Icon d="M6 12h12" />
        </button>
        <span className="paper-viewer__zoom-level" aria-live="polite">
          {level === "fit" && fitted === null ? words.fit : written(shown, words.digits)}
        </span>
        <button
          type="button"
          className="paper-viewer__button"
          aria-label={words.in}
          title={words.in}
          aria-disabled={atMax || undefined}
          onClick={() => by(1)}
        >
          <Icon d="M12 6v12M6 12h12" />
        </button>
        <button
          type="button"
          className="paper-viewer__button"
          aria-label={words.fit}
          title={words.fit}
          aria-pressed={level === "fit"}
          onClick={() => setLevel("fit")}
        >
          <Icon d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15" />
        </button>
        {end}
      </div>
      <div
        className="paper-viewer__canvas"
        ref={canvas}
        tabIndex={0}
        aria-label={canvasLabel}
        data-zoom={level === "fit" ? "fit" : String(level)}
      >
        {children}
      </div>
    </div>
  );
}
