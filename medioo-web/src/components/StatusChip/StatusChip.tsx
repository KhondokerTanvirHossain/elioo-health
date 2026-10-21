import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import "./StatusChip.css";

/** The chip of one value: inside, outside (above or below), critical (above or below), or unknown. */
export type ChipKind = "in" | "out-high" | "out-low" | "crit-high" | "crit-low" | "unk";

export const CHIP_KINDS: readonly ChipKind[] = ["in", "out-high", "out-low", "crit-high", "crit-low", "unk"];

/**
 * The word, one to one with the keys Java's PaperDisplay.chipKey emits beside the same value (`chip_key` in the
 * /app/api JSON); StatusChip.test.tsx checks every fixture value's key against this table.
 */
export const CHIP_KEY: Record<ChipKind, CopyKey> = {
  in: "value.chip.inside",
  "out-high": "value.chip.outside.high",
  "out-low": "value.chip.outside.low",
  "crit-high": "value.chip.critical.high",
  "crit-low": "value.chip.critical.low",
  unk: "value.chip.unknown",
};

type Shape = "circle" | "arrow-up" | "arrow-down" | "double-arrow-up" | "double-arrow-down" | "question";

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
};

/**
 * The one place an API value becomes a chip kind: a paper's value passes its `display_kind` and `side`, a lab point
 * its `kind` and `side`. Critical (red) exactly when the kind is critical AND a side is set; a sideless critical or
 * outside has no direction to show and is unknown, as Java's PaperDisplay.kindOf makes it. Matching is exact and fails
 * closed: anything this table does not know (`CRITICAL`, a later additive verdict, an empty string) is unknown, never a
 * direction. The parameters are `string` because this is the API boundary: what arrives is not yet known to be typed.
 */
export function chipKindOf(kind: string, side: string | null | undefined): ChipKind {
  if (side === "high" || side === "low") {
    if (kind === "critical") return `crit-${side}`;
    if (kind === "outside") return `out-${side}`;
  }
  return kind === "inside" ? "in" : "unk";
}

/** Colour + shape + word. Only the two critical kinds paint with the NOW tokens. */
export function StatusChip({ kind, lang }: { kind: ChipKind; lang: Lang }) {
  const shape = CHIP_SHAPE[kind];
  return (
    <span className={`status-chip status-chip--${kind}`}>
      <svg className="status-chip__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape={shape}>
        {DRAWN[shape]}
      </svg>
      <span className="status-chip__word">{t(lang, CHIP_KEY[kind])}</span>
    </span>
  );
}
