import { t, type CopyKey, type Lang } from "@/copy";
import { ChipShape, type Shape } from "@/components/StatusChip/StatusChip";
import "./UrgencyTag.css";

/** The two stored levels a paper's row says (D27: ROUTINE and an unassessed paper say nothing), with their shape and word. */
const LEVEL: Record<"now" | "this_week", { shape: Shape; key: CopyKey }> = {
  now: { shape: "octagon", key: "urgency.now.short" },
  this_week: { shape: "calendar-week", key: "urgency.this_week.short" },
};

/**
 * A paper's stored urgency on a row (plan W8: the papers list and the attention strip), from the field Java sent
 * (`chip` on a timeline entry, `level` on an attention item): the NOW octagon or the this-week calendar, and the short
 * form WhatsApp's bundle holds (`urgency.*.short`, as Java's UrgencyChip says it). Exact matching that fails closed: any
 * value but "now" and "this_week" renders nothing, never a guess. It lives under safety/, the one place an urgency line
 * may be named (eslint SAFETY_LITERAL_SYNTAX). It paints no colour of its own: shape and word, in the row's text colour.
 * Whether a row's NOW tag should carry the NOW red as MonthCalendar's day mark does (PO ruling R2) is the PO's call.
 */
export function UrgencyTag({ level, lang }: { level: string | null; lang: Lang }) {
  if (level !== "now" && level !== "this_week") return null;
  const { shape, key } = LEVEL[level];
  return (
    <span className="urgency-tag" data-urgency={level}>
      <ChipShape shape={shape} className="urgency-tag__shape" />
      <span className="urgency-tag__word">{t(lang, key)}</span>
    </span>
  );
}
