import { t, type CopyKey, type Lang } from "@/copy";
import { ChipShape, type Shape } from "@/components/StatusChip/StatusChip";
import "./UrgencyTag.css";

/**
 * The two stored levels a paper's row says (D27: ROUTINE and an unassessed paper say nothing), with their shape, word
 * and colour modifier (PO ruling P5): NOW the NOW red, THIS_WEEK the amber.
 */
const LEVEL: Record<"now" | "this_week", { shape: Shape; key: CopyKey; modifier: string }> = {
  now: { shape: "octagon", key: "urgency.now.short", modifier: "urgency-tag--now" },
  this_week: { shape: "calendar-week", key: "urgency.this_week.short", modifier: "urgency-tag--week" },
};

/**
 * A paper's stored urgency on a row (plan W8: the papers list and the attention strip), from the field Java sent
 * (`chip` on a timeline entry, `level` on an attention item): the NOW octagon or the this-week calendar, and the short
 * form WhatsApp's bundle holds (`urgency.*.short`, as Java's UrgencyChip says it). Exact matching that fails closed: any
 * value but "now" and "this_week" renders nothing, never a guess. It lives under safety/, the one place an urgency line
 * may be named (eslint SAFETY_LITERAL_SYNTAX). PO ruling P5 (2026-10-28) colours it as the approved prototype's
 * paperCell does: NOW is `chip crit` (the NOW wash and ink), THIS_WEEK `chip out` (the amber wash and amber), each
 * keeping its shape and word, so colour is never the only signal and red stays NOW's alone.
 */
export function UrgencyTag({ level, lang }: { level: string | null; lang: Lang }) {
  if (level !== "now" && level !== "this_week") return null;
  const { shape, key, modifier } = LEVEL[level];
  return (
    <span className={`urgency-tag ${modifier}`} data-urgency={level}>
      <ChipShape shape={shape} className="urgency-tag__shape" />
      <span className="urgency-tag__word">{t(lang, key)}</span>
    </span>
  );
}
