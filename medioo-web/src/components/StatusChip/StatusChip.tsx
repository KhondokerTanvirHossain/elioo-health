import { t, type CopyKey, type Lang } from "@/copy";
import { StatusChipView, type ChipKind } from "./StatusChipView";

export { CHIP_KINDS, CHIP_SHAPE, ChipShape, StatusChipView, type ChipKind, type Shape } from "./StatusChipView";

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
  return <StatusChipView kind={kind} word={t(lang, CHIP_KEY[kind])} />;
}
