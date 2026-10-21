package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.PrintedRange;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.domain.PaperDisplay;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * ValueRow (spec §9, F2): one shown value, drawn from its stored verdict and nothing else. Its class, its chip word and
 * its range bar come from the verdict; the bar's pin is placed by {@link PrintedRange#pinPercent}, the verdict's own
 * parser, and only where it agrees with the verdict. Nothing here calls UrgencyService (HexagonalArchitectureTest).
 * Every row sits beside its stored crop (DR-12): a value with no crop is never drawn.
 */
final class ValueRows {

    private ValueRows() {
    }

    /** {@link PaperDisplay#kindOf}: the kind a row is drawn as, from its stored verdict (F2). */
    static ValueVerdict.Kind kindOf(Map<String, Object> v) {
        return PaperDisplay.kindOf(v);
    }

    /** {@link PaperDisplay#storedSide}. */
    static String storedSide(Map<String, Object> v) {
        return PaperDisplay.storedSide(v);
    }

    /** {@link PaperDisplay#pin}: the pin only where it agrees with the verdict. */
    static OptionalDouble pin(ValueVerdict.Kind kind, Map<String, Object> v, String storedSide) {
        return PaperDisplay.pin(kind, v, storedSide);
    }

    /** {@link PaperDisplay#chipKey}: the chip's key, a side only from the stored verdict. */
    static String chipKey(ValueVerdict.Kind kind, String storedSide) {
        return PaperDisplay.chipKey(kind, storedSide);
    }

    /** Where a value sheet was opened from, so its close goes back to the same row: the summary, or the detail. */
    static final String FROM_SUMMARY = "summary";
    static final String FROM_ALL = "all";

    /** A row on the result summary: {@link #row(UiCopy, Lang, UUID, Map, String)} opened from {@link #FROM_SUMMARY}. */
    static String row(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> v) {
        return row(copy, lang, documentId, v, FROM_SUMMARY);
    }

    /**
     * One value row: a link to its value sheet (B6, s13; the crop itself for a legacy row with no observation id), the
     * name, the value as printed with its unit, the crop, the bar where the code placed the value, the printed range and
     * the chip. {@code from} is the page the row is drawn on, so the sheet closes back to this row there.
     */
    static String row(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> v, String from) {
        String crop = crop(v.get("crop_key"), "a value");
        ValueVerdict.Kind kind = kindOf(v);
        String cls = kind.name().toLowerCase(Locale.ROOT);
        // the stored side belongs to the stored verdict: a demoted INSIDE or an UNKNOWN has none
        String side = PaperDisplay.drawnSide(kind, v);
        OptionalDouble pin = pin(kind, v, side);
        String chip = copy.t(lang, chipKey(kind, side));
        String range = range(copy, lang, v);
        String src = PageChrome.imageHref(documentId, crop);
        String href = v.get("id") == null ? src : sheetHref(documentId, v.get("id"), from);
        String unit = v.get("unit") == null ? "" : esc(v.get("unit"));
        StringBuilder b = new StringBuilder("<a class=\"vrow ").append(cls).append('"');
        if (v.get("id") != null) {
            b.append(" id=\"v-").append(esc(v.get("id"))).append('"');
        }
        b.append(" href=\"").append(href).append("\" aria-label=\"")
                .append(esc(v.get("name"))).append(' ').append(esc(v.get("value"))).append(unit.isEmpty() ? "" : " " + unit)
                .append(", ").append(range).append(", ").append(chip).append("\">")
                .append("<span class=\"vhead\"><span class=\"name\">").append(esc(v.get("name"))).append("</span> ")
                .append("<span class=\"val\">").append(esc(v.get("value"))).append("</span>")
                .append(unit.isEmpty() ? "" : " <span class=\"unit\">" + unit + "</span>").append("</span>")
                .append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(src).append("\">");
        pin.ifPresent(p -> b.append(bar(v, kind, p)));
        return b.append("<span class=\"vfoot\"><span class=\"range\">").append(range).append("</span> <span class=\"chip ")
                .append(cls).append("\">").append(chip).append("</span></span></a>").toString();
    }

    /** The value sheet's URL (s13) for one observation of this document; {@code from} is only ever "all" or "summary". */
    static String sheetHref(UUID documentId, Object observationId, String from) {
        return PageChrome.BASE + "/documents/" + documentId + "/values/" + esc(observationId) + "?from="
                + (FROM_ALL.equals(from) ? FROM_ALL : FROM_SUMMARY);
    }

    /**
     * The value sheet's value (s13): the value and unit as printed, large; the bar only where {@link #pin} agrees with the
     * stored verdict; the printed range and the chip. The same verdict reading as {@link #row}, so the sheet and the row
     * it opened from can never tell two stories.
     */
    static String sheetValue(UiCopy copy, Lang lang, Map<String, Object> v) {
        ValueVerdict.Kind kind = kindOf(v);
        String cls = kind.name().toLowerCase(Locale.ROOT);
        String side = PaperDisplay.drawnSide(kind, v);
        OptionalDouble pin = pin(kind, v, side);
        String chip = copy.t(lang, chipKey(kind, side));
        String range = range(copy, lang, v);
        String unit = v.get("unit") == null ? "" : esc(v.get("unit"));
        StringBuilder b = new StringBuilder("<div class=\"sheet-value ").append(cls).append("\" role=\"group\" aria-label=\"")
                .append(esc(v.get("name"))).append(' ').append(esc(v.get("value"))).append(unit.isEmpty() ? "" : " " + unit)
                .append(", ").append(range).append(", ").append(chip).append("\">")
                .append("<span class=\"vhead\"><span class=\"val big\">").append(esc(v.get("value"))).append("</span>")
                .append(unit.isEmpty() ? "" : " <span class=\"unit\">" + unit + "</span>").append("</span>");
        pin.ifPresent(p -> b.append(bar(v, kind, p)));
        return b.append("<span class=\"vfoot\"><span class=\"range\">").append(range).append("</span> <span class=\"chip ")
                .append(cls).append("\">").append(chip).append("</span></span></div>").toString();
    }

    /** Server SVG: geometry as attributes, colour from classes (CSP style-src 'self'; DesignTokensTest forbids literals). */
    static String bar(Map<String, Object> v, ValueVerdict.Kind kind, double pin) {
        return "<svg class=\"bar\" viewBox=\"0 0 100 10\" aria-hidden=\"true\" focusable=\"false\">"
                + "<rect class=\"track\" x=\"0\" y=\"4\" width=\"100\" height=\"2\"/>"
                + "<rect class=\"band\" x=\"25\" y=\"2\" width=\"50\" height=\"6\"/>"
                + "<circle class=\"pin " + kind.name().toLowerCase(Locale.ROOT) + "\" cx=\"" + String.format(Locale.ROOT, "%.1f", pin)
                + "\" cy=\"5\" r=\"4\"/></svg>"
                + "<span class=\"ends\" aria-hidden=\"true\"><span>" + esc(v.get("ref_low")) + "</span><span>" + esc(v.get("ref_high")) + "</span></span>";
    }

    /**
     * MedicineRow (spec §9, principle 7): the crop first and large — what a pharmacy is shown — then the line as written,
     * quiet, joining only the fields that were written. No colour, no chip, no status: Medioo says nothing about a medicine.
     */
    static String medicine(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> m) {
        String href = PageChrome.imageHref(documentId, crop(m.get("crop_key"), "a medicine"));
        String caption = PaperDisplay.MEDICINE_FIELDS.stream().map(m::get).filter(Objects::nonNull).map(f -> String.valueOf(f).strip())
                .filter(f -> !f.isEmpty()).map(Html::esc).collect(Collectors.joining(" · "));
        return "<a class=\"med\" href=\"" + href + "\"><img class=\"crop big\" loading=\"lazy\" alt=\"\" src=\"" + href + "\">"
                + "<span class=\"cap\">" + caption + "</span><span class=\"zoom\">" + copy.t(lang, "crop.zoom") + "</span></a>";
    }

    /**
     * কাগজে লেখা (principle 6): a line from the paper, verbatim, under the paper label and beside its crop. Medioo's own
     * words never use this block, and this block never carries Medioo's words (PaperIsNeverMediooTest). {@code after} is
     * markup the caller already escaped (a follow-up's date).
     */
    static String paperQuote(UiCopy copy, Lang lang, UUID documentId, Object text, Object cropKey, String after) {
        String href = PageChrome.imageHref(documentId, crop(cropKey, "a line from the paper"));
        return "<figure class=\"paper\"><figcaption class=\"label\">" + copy.t(lang, "paper.label") + "</figcaption>"
                + "<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"" + href + "\"><blockquote>" + esc(text) + "</blockquote>"
                + after + "</figure>";
    }

    static String crop(Object key, String what) {
        if (key == null || String.valueOf(key).isBlank()) {
            throw new IllegalStateException(what + " without its crop is never shown");
        }
        return String.valueOf(key);
    }

    /** The range as printed: both bounds, one, or none. Strings throughout, so the paper's digits are kept. */
    static String range(UiCopy copy, Lang lang, Map<String, Object> v) {
        Object low = v.get("ref_low");
        Object high = v.get("ref_high");
        if (low != null && high != null) {
            return copy.t(lang, "value.range", String.valueOf(low), String.valueOf(high));
        }
        if (low != null || high != null) {
            return copy.t(lang, "value.range.one", String.valueOf(low != null ? low : high));
        }
        return copy.t(lang, "value.range.none");
    }
}
