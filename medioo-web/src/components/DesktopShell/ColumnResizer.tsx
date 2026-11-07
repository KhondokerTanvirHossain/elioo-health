"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";

/** The narrowest the conversation goes: its cards (min(320px, 92%)) and the composer still fit. */
export const TALK_MIN = 320;
/** The room the paper always keeps: its toolbar and a page at a readable size. */
export const PAPER_MIN = 420;
/** One arrow press; Shift moves four. */
const STEP = 16;
/** The design's width (--conversation-w), said as the value until the island has measured the real one. */
const DESIGN_WIDTH = 400;
/** Where this browser remembers the width (a per-viewer convenience: nothing breaks when it is gone). */
export const STORE_KEY = "medioo.desk.talk-w";

/** The conversation's width for a wish, held between TALK_MIN and what leaves the paper PAPER_MIN of `columns`. */
export function talkWidth(wish: number, columns: number): number {
  return Math.round(Math.max(TALK_MIN, Math.min(wish, columns - PAPER_MIN)));
}

function remembered(): number | null {
  try {
    const n = Number(window.localStorage.getItem(STORE_KEY));
    return Number.isFinite(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}
function remember(width: number | null) {
  try {
    if (width === null) window.localStorage.removeItem(STORE_KEY);
    else window.localStorage.setItem(STORE_KEY, String(width));
  } catch {
    // A private window or blocked storage: the width holds for this page and is forgotten after.
  }
}

/**
 * The divider between the paper and the conversation (PO 2026-11-07: "the chat window vs paper windows should be
 * resizable"; desktop.html has the conversation at a fixed --cw). It sits between the two columns in the shell's
 * columns grid and sets the conversation's width as `--talk-w` on that grid, through the CSSOM, never a style
 * attribute (style-src 'self'); DesktopShell.css clamps it again, so a remembered width can never squeeze the paper on
 * a narrower window.
 * - drag it (the pointer is captured, so it follows past either column), or move it with the arrow keys (Shift: four
 *   steps), Home and End to either bound; it is a focusable `separator` whose value is the conversation's width;
 * - Enter or a double click puts the design's width back (400 px, 360 below 1200 px);
 * - the width is remembered on this browser and comes back on the next visit.
 */
export function ColumnResizer({ label }: { label: string }) {
  const self = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; width: number; id: number } | null>(null);
  const [now, setNow] = useState<number | null>(null);
  const [max, setMax] = useState<number | null>(null);

  const columns = () => self.current?.parentElement ?? null;
  const talk = () => self.current?.nextElementSibling ?? null;
  const columnsWidth = () => columns()?.getBoundingClientRect().width ?? 0;

  function apply(width: number | null) {
    const grid = columns();
    if (grid === null) return;
    if (width === null) {
      grid.style.removeProperty("--talk-w");
      setNow(Math.round(talk()?.getBoundingClientRect().width ?? 0) || null);
    } else {
      grid.style.setProperty("--talk-w", `${width}px`);
      setNow(width);
    }
  }

  useEffect(() => {
    const grid = columns();
    if (grid === null) return;
    const measure = () => {
      const w = columnsWidth();
      setMax(w > 0 ? Math.max(TALK_MIN, Math.round(w - PAPER_MIN)) : null);
    };
    measure();
    const kept = remembered();
    if (kept !== null) apply(kept);
    else setNow(Math.round(talk()?.getBoundingClientRect().width ?? 0) || null);
    if (typeof ResizeObserver !== "function") {
      window.addEventListener("resize", measure);
      return () => window.removeEventListener("resize", measure);
    }
    const watch = new ResizeObserver(measure);
    watch.observe(grid);
    return () => watch.disconnect();
    // Once, on mount: the grid and its talk column are the same elements for the shell's life.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const current = () => now ?? Math.round(talk()?.getBoundingClientRect().width ?? 0);
  const set = (wish: number) => {
    const width = talkWidth(wish, columnsWidth());
    apply(width);
    return width;
  };
  const reset = () => {
    apply(null);
    remember(null);
  };

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const step = e.shiftKey ? STEP * 4 : STEP;
    let width: number;
    if (e.key === "ArrowLeft") width = set(current() + step);
    else if (e.key === "ArrowRight") width = set(current() - step);
    else if (e.key === "Home") width = set(TALK_MIN);
    else if (e.key === "End") width = set(Number.POSITIVE_INFINITY);
    else if (e.key === "Enter") {
      e.preventDefault();
      reset();
      return;
    } else return;
    e.preventDefault();
    remember(width);
  }

  function onPointerDown(e: PointerEvent<HTMLDivElement>) {
    if (e.button !== 0) return;
    e.preventDefault();
    drag.current = { x: e.clientX, width: current(), id: e.pointerId };
    if (typeof e.currentTarget.setPointerCapture === "function") e.currentTarget.setPointerCapture(e.pointerId);
    self.current?.closest(".desktop-shell")?.setAttribute("data-resizing", "");
  }
  function onPointerMove(e: PointerEvent<HTMLDivElement>) {
    const d = drag.current;
    if (d === null || e.pointerId !== d.id) return;
    // The divider follows the pointer: moved left, the conversation (on the right) grows by as much.
    set(d.width + (d.x - e.clientX));
  }
  function onPointerEnd(e: PointerEvent<HTMLDivElement>) {
    const d = drag.current;
    if (d === null || e.pointerId !== d.id) return;
    drag.current = null;
    self.current?.closest(".desktop-shell")?.removeAttribute("data-resizing");
    remember(set(d.width + (d.x - e.clientX)));
  }

  return (
    <div
      ref={self}
      className="desktop-shell__resizer"
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      title={label}
      tabIndex={0}
      aria-valuenow={now ?? DESIGN_WIDTH}
      aria-valuemin={TALK_MIN}
      aria-valuemax={max ?? undefined}
      onKeyDown={onKeyDown}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      onDoubleClick={reset}
    />
  );
}
