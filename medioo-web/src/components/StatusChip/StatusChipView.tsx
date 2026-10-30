import type { ReactNode } from "react";
import "./StatusChip.css";

/*
 * The chip's drawing, with no copy in it (plan W6): what a "use client" row may import (eslint and
 * built/client-copy.built.test.ts refuse @/copy there). StatusChip.tsx resolves the word on the server and draws it
 * with StatusChipView; it re-exports everything here, so every other importer keeps its one door.
 */

/** The chip of one value: inside, outside (above or below), critical (above or below), or unknown. */
export type ChipKind = "in" | "out-high" | "out-low" | "crit-high" | "crit-low" | "unk";

export const CHIP_KINDS: readonly ChipKind[] = ["in", "out-high", "out-low", "crit-high", "crit-low", "unk"];

/**
 * Every shape a status is drawn with: the six chip kinds', and the two urgency shapes a paper's band and the NOW banner
 * draw (`octagon` for NOW, `calendar-week` for this week), for a mark that shows a paper's urgency rather than a value's
 * kind (MonthCalendar's day). An urgency is never a direction: a NOW paper can be NOW for a critically low value.
 */
export type Shape =
  | "circle"
  | "arrow-up"
  | "arrow-down"
  | "double-arrow-up"
  | "double-arrow-down"
  | "question"
  | "octagon"
  | "calendar-week";

/** The shape, so a status is never colour alone (legibility floor): six kinds, six shapes. */
export const CHIP_SHAPE: Record<ChipKind, Shape> = {
  in: "circle",
  "out-high": "arrow-up",
  "out-low": "arrow-down",
  "crit-high": "double-arrow-up",
  "crit-low": "double-arrow-down",
  unk: "question",
};

// 24 × 24 strokes, painted with the chip's own text colour (StatusChip.css): no colour is named here.
const DRAWN: Record<Shape, ReactNode> = {
  circle: <circle cx="12" cy="12" r="6.5" />,
  "arrow-up": <path d="M12 19V6M7 11l5-5 5 5" />,
  "arrow-down": <path d="M12 5v13M7 13l5 5 5-5" />,
  "double-arrow-up": <path d="M7 12l5-5 5 5M7 18l5-5 5 5" />,
  "double-arrow-down": <path d="M7 6l5 5 5-5M7 12l5 5 5-5" />,
  question: <path d="M9.2 9.3a2.9 2.9 0 1 1 4.1 2.6c-.8.4-1.3 1-1.3 1.9v.6M12 17.6v.01" />,
  // NowBanner's and VerdictCard's NOW octagon, and VerdictCard's this-week calendar, path for path (MonthCalendar.test
  // compares the drawn markup with theirs, so the three cannot drift apart).
  octagon: (
    <>
      <path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z" />
      <path d="M12 7.5v5.5M12 16.2v.01" />
    </>
  ),
  "calendar-week": (
    <>
      <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
      <path d="M3.5 10h17M8 3v4M16 3v4M12 13v3" />
    </>
  ),
};

/**
 * A chip's shape on its own, for a mark whose status is said elsewhere (MonthCalendar's NOW day, TrendChart's points):
 * the same drawing as the chip, decorative, painted by the caller's class. `x`, `y` and `size` place it inside another
 * SVG. No colour is named here, and the caller's stylesheet decides it (only the NOW components may paint it red).
 */
export function ChipShape({ shape, className, x, y, size }: { shape: Shape; className: string; x?: number; y?: number; size?: number }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
      data-shape={shape}
      x={x}
      y={y}
      width={size}
      height={size}
    >
      {DRAWN[shape]}
    </svg>
  );
}

/** Colour + shape + the word the server resolved. Only the two critical kinds paint with the NOW tokens. */
export function StatusChipView({ kind, word }: { kind: ChipKind; word: string }) {
  return (
    <span className={`status-chip status-chip--${kind}`}>
      <ChipShape shape={CHIP_SHAPE[kind]} className="status-chip__shape" />
      <span className="status-chip__word">{word}</span>
    </span>
  );
}
