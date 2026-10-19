package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.PrintedRange;
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
 * its range bar come from the verdict; the bar's pin is placed by {@link PrintedRange#pinPercent}, the verdict's own
 * parser, and only where it agrees with the verdict. Nothing here calls UrgencyService (HexagonalArchitectureTest).
 * Every row sits beside its stored crop (DR-12): a value with no crop is never drawn.
 */
final class ValueRows {

    private ValueRows() {
    }

    /**
     * F2 on the page: the stored verdict; NULL or anything unrecognised is UNKNOWN, and a stored INSIDE is shown INSIDE
     * only when the code proves it on the row as rendered (insideByCode). A stored OUTSIDE or CRITICAL without a stored
     * side (a row V19's observation_verdict_side_matches_kind forbids) is UNKNOWN too: the page never guesses a direction,
     * not even from the pin. The direction that withholds reassurance wins.
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
        if (kind == ValueVerdict.Kind.INSIDE && !PrintedRange.insideByCode(v)) {
            return ValueVerdict.Kind.UNKNOWN;
        }
        boolean sided = kind == ValueVerdict.Kind.OUTSIDE || kind == ValueVerdict.Kind.CRITICAL;
        return sided && storedSide(v) == null ? ValueVerdict.Kind.UNKNOWN : kind;
    }

    /** The stored verdict_side when it is one the page knows ("high" or "low"), else null. */
    static String storedSide(Map<String, Object> v) {
        Object side = v.get("verdict_side");
        return "high".equals(side) || "low".equals(side) ? (String) side : null;
    }

    /**
     * The pin only when it agrees with the verdict: INSIDE in the band, OUTSIDE/CRITICAL outside it and on the stored side
     * ({@code storedSide}), UNKNOWN never. A bar that would tell a different story from the chip is
     * not drawn; the chip keeps the stored verdict either way.
     */
    static OptionalDouble pin(ValueVerdict.Kind kind, Map<String, Object> v, String storedSide) {
        if (kind == ValueVerdict.Kind.UNKNOWN) {
            return OptionalDouble.empty();
        }
        OptionalDouble p = PrintedRange.pinPercent(v);
        if (p.isEmpty()) {
            return p;
        }
        double at = p.getAsDouble();
        boolean inBand = at >= 25 && at <= 75;
        if (inBand != (kind == ValueVerdict.Kind.INSIDE)) {
            return OptionalDouble.empty();
        }
        boolean otherSide = ("high".equals(storedSide) && at < 25) || ("low".equals(storedSide) && at > 75);
        return otherSide ? OptionalDouble.empty() : p;
    }

    /**
     * The chip's key. OUTSIDE and CRITICAL carry the stored verdict's side ({@code verdict_side}) and only that: the page
     * never re-judges a stored verdict, and never reads a side off the pin. {@link #kindOf} has already made a sideless
     * OUTSIDE or CRITICAL UNKNOWN, so reaching here without a side is a bug, not a row to guess at.
     */
    static String chipKey(ValueVerdict.Kind kind, String storedSide) {
        return switch (kind) {
            case INSIDE -> "value.chip.inside";
            case UNKNOWN -> "value.chip.unknown";
            case OUTSIDE, CRITICAL -> {
                if (storedSide == null) {
                    throw new IllegalStateException(kind + " without its stored side is never given a direction");
                }
                yield "value.chip." + kind.name().toLowerCase(Locale.ROOT) + "." + storedSide;
            }
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
        // the stored side belongs to the stored verdict: a demoted INSIDE or an UNKNOWN has none
        String side = kind == ValueVerdict.Kind.INSIDE || kind == ValueVerdict.Kind.UNKNOWN ? null : storedSide(v);
        OptionalDouble pin = pin(kind, v, side);
        String chip = copy.t(lang, chipKey(kind, side));
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
