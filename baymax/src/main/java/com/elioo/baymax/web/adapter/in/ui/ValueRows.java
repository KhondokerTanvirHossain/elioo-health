package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.ValueVerdict;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * ValueRow (spec §9, F2): one shown value, drawn from its stored verdict and nothing else. Its class, its chip word and
 * its range bar come from the verdict; the bar's pin is placed by {@link UrgencyService#pinPercent}, the verdict's own
 * parser, and only where it agrees with the verdict. Every row sits beside its stored crop (DR-12): a value with no
 * crop is never drawn.
 */
final class ValueRows {

    private ValueRows() {
    }

    /**
     * F2 on the page: the stored verdict; NULL or anything unrecognised is UNKNOWN, and a stored INSIDE is shown INSIDE
     * only when the code proves it on the row as rendered (insideByCode). The direction that withholds reassurance wins.
     */
    static ValueVerdict.Kind kindOf(Map<String, Object> v) {
        Object raw = v.get("verdict");
        if (raw == null) {
            return ValueVerdict.Kind.UNKNOWN;
        }
        ValueVerdict.Kind kind;
        try {
            kind = ValueVerdict.Kind.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ValueVerdict.Kind.UNKNOWN;
        }
        return kind == ValueVerdict.Kind.INSIDE && !UrgencyService.insideByCode(v) ? ValueVerdict.Kind.UNKNOWN : kind;
    }

    /** The pin only when it agrees with the verdict: INSIDE in the band, OUTSIDE/CRITICAL outside it, UNKNOWN never. */
    static OptionalDouble pin(ValueVerdict.Kind kind, Map<String, Object> v) {
        if (kind == ValueVerdict.Kind.UNKNOWN) {
            return OptionalDouble.empty();
        }
        OptionalDouble p = UrgencyService.pinPercent(v);
        if (p.isEmpty()) {
            return p;
        }
        boolean inBand = p.getAsDouble() >= 25 && p.getAsDouble() <= 75;
        return inBand == (kind == ValueVerdict.Kind.INSIDE) ? p : OptionalDouble.empty();
    }

    /**
     * The chip's key. OUTSIDE and CRITICAL carry a side: the stored verdict's ({@code verdict_side}) when there is one —
     * the page never re-judges a stored verdict — else the side the agreeing pin shows, else no side at all.
     */
    static String chipKey(ValueVerdict.Kind kind, Object storedSide, OptionalDouble pin) {
        String side = "high".equals(storedSide) ? ".high" : "low".equals(storedSide) ? ".low"
                : pin.isEmpty() ? "" : pin.getAsDouble() > 75 ? ".high" : pin.getAsDouble() < 25 ? ".low" : "";
        return switch (kind) {
            case INSIDE -> "value.chip.inside";
            case UNKNOWN -> "value.chip.unknown";
            case OUTSIDE -> "value.chip.outside" + side;
            case CRITICAL -> "value.chip.critical" + side;
        };
    }

    /**
     * One value row: a link to its crop (B6 points it at the value sheet), the name, the value as printed with its unit,
     * the crop, the bar where the code placed the value, the printed range and the chip.
     */
    static String row(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> v) {
        String crop = crop(v.get("crop_key"), "a value");
        ValueVerdict.Kind kind = kindOf(v);
        String cls = kind.name().toLowerCase(Locale.ROOT);
        OptionalDouble pin = pin(kind, v);
        // the stored side belongs to the stored verdict: a demoted INSIDE or an UNKNOWN has none
        String chip = copy.t(lang, chipKey(kind, kind == ValueVerdict.Kind.INSIDE || kind == ValueVerdict.Kind.UNKNOWN
                ? null : v.get("verdict_side"), pin));
        String range = range(copy, lang, v);
        String href = PageChrome.imageHref(documentId, crop);
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
                .append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(href).append("\">");
        pin.ifPresent(p -> b.append(bar(v, kind, p)));
        return b.append("<span class=\"vfoot\"><span class=\"range\">").append(range).append("</span> <span class=\"chip ")
                .append(cls).append("\">").append(chip).append("</span></span></a>").toString();
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

    /** The fields a medicine line is captioned with, in the order the prescription writes them. */
    private static final List<String> MEDICINE_FIELDS = List.of("name", "dose_text", "frequency_text", "timing_text", "duration_text");

    /**
     * MedicineRow (spec §9, principle 7): the crop first and large — what a pharmacy is shown — then the line as written,
     * quiet, joining only the fields that were written. No colour, no chip, no status: Medioo says nothing about a medicine.
     */
    static String medicine(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> m) {
        String href = PageChrome.imageHref(documentId, crop(m.get("crop_key"), "a medicine"));
        String caption = MEDICINE_FIELDS.stream().map(m::get).filter(Objects::nonNull).map(f -> String.valueOf(f).strip())
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

    private static String crop(Object key, String what) {
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
