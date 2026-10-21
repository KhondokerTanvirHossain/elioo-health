package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.PrintedRange;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * How a stored paper is displayed (rebuild plan A5, B2): the kind each value is drawn as, its chip, its pin, the order a
 * NOW paper's reasons are drawn in, which rows its reason blocks draw, and the header's outside count. Pure rules over
 * stored rows, in the domain so the HTML pages ({@code ValueRows}, {@code DocumentPages}) and the {@code /app/api} JSON
 * read one rule and can never tell two stories; React renders what this decides and never derives it. Nothing here calls
 * UrgencyService (HexagonalArchitectureTest): the pure printed-range reads are {@link PrintedRange}'s.
 */
public final class PaperDisplay {

    /**
     * The reasons the rule raises a paper to NOW with (UrgencyService; derived from it in ResultNowRenderTest). Their
     * blocks come first; the THIS_WEEK reasons a NOW paper also carries follow them (spec §4.11, "shown below if present").
     */
    public static final Set<UrgencyReason> NOW_REASONS = EnumSet.of(UrgencyReason.VALUE_CRITICAL, UrgencyReason.UNVERIFIED_VALUE_CRITICAL,
            UrgencyReason.TEXT_EMERGENCY_PHRASE);

    /**
     * The fields a medicine line was written with, in the order the prescription writes them: what the HTML caption joins
     * ({@code ValueRows.medicine}) and the only fields the JSON sends ({@code PaperJson}). Nothing derived — never the
     * extraction's purpose, status or confidence (DR-16).
     */
    public static final List<String> MEDICINE_FIELDS = List.of("name", "dose_text", "frequency_text", "timing_text", "duration_text");

    private PaperDisplay() {
    }

    /**
     * F2 on the page: the stored verdict; NULL or anything unrecognised is UNKNOWN, and a stored INSIDE is shown INSIDE
     * only when the code proves it on the row as rendered (insideByCode). A stored OUTSIDE or CRITICAL without a stored
     * side (a row V19's observation_verdict_side_matches_kind forbids) is UNKNOWN too: the page never guesses a direction,
     * not even from the pin. The direction that withholds reassurance wins.
     */
    public static ValueVerdict.Kind kindOf(Map<String, Object> v) {
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
    public static String storedSide(Map<String, Object> v) {
        Object side = v.get("verdict_side");
        return "high".equals(side) || "low".equals(side) ? (String) side : null;
    }

    /** The side a row is drawn with: the stored side of a drawn OUTSIDE or CRITICAL; a demoted INSIDE or an UNKNOWN has none. */
    public static String drawnSide(ValueVerdict.Kind kind, Map<String, Object> v) {
        return kind == ValueVerdict.Kind.INSIDE || kind == ValueVerdict.Kind.UNKNOWN ? null : storedSide(v);
    }

    /**
     * The pin only when it agrees with the verdict: INSIDE in the band, OUTSIDE/CRITICAL outside it and on the stored side
     * ({@code storedSide}), UNKNOWN never. A bar that would tell a different story from the chip is
     * not drawn; the chip keeps the stored verdict either way.
     */
    public static OptionalDouble pin(ValueVerdict.Kind kind, Map<String, Object> v, String storedSide) {
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
    public static String chipKey(ValueVerdict.Kind kind, String storedSide) {
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
     * The blocks of a NOW page, in the order drawn (spec §4.11): one per distinct stored reason, empty for a prefix the
     * enum does not know. The NOW reasons ({@link #NOW_REASONS}) and the unknown ones (their level is not known, so they
     * are not demoted) first, then the THIS_WEEK reasons "below if present", each group in stored order. The rule stores
     * values in printed order and the phrase last, so the stored order alone would put a THIS_WEEK block above the reason
     * the paper is NOW for. With no NOW reason stored (a legacy row; the rule never stores one) the generic block comes
     * first, so a NOW page always says why it is NOW.
     */
    public static List<Optional<UrgencyReason>> drawnOrder(List<String> stored) {
        Set<Optional<UrgencyReason>> distinct = new LinkedHashSet<>();
        (stored == null ? List.<String>of() : stored).forEach(reason -> distinct.add(UrgencyReason.ofReason(reason)));
        List<Optional<UrgencyReason>> first = new ArrayList<>();
        List<Optional<UrgencyReason>> below = new ArrayList<>();
        for (Optional<UrgencyReason> reason : distinct) {
            (reason.isEmpty() || NOW_REASONS.contains(reason.get()) ? first : below).add(reason);
        }
        if (first.isEmpty()) {
            first.add(Optional.empty());
        }
        first.addAll(below);
        return first;
    }

    /**
     * The value kinds a NOW page's reason blocks draw: CRITICAL under a stored value_critical, nothing below NOW. An
     * OUTSIDE row stays in "যেগুলো সীমার বাইরে" below, as s11 draws it; its block points there. The page's NOW block, its
     * groups below, and the JSON's {@code in_now_block} all read this one set.
     */
    public static Set<ValueVerdict.Kind> shownAbove(Urgency level, List<String> reasons) {
        if (level != Urgency.NOW) {
            return Set.of();
        }
        Set<ValueVerdict.Kind> kinds = EnumSet.noneOf(ValueVerdict.Kind.class);
        for (String reason : reasons == null ? List.<String>of() : reasons) {
            UrgencyReason.ofReason(reason).flatMap(PaperDisplay::kindShownBy).ifPresent(kinds::add);
        }
        return kinds;
    }

    /** The value kind a reason's block draws its rows of, if it draws rows at all. */
    public static Optional<ValueVerdict.Kind> kindShownBy(UrgencyReason reason) {
        return reason == UrgencyReason.VALUE_CRITICAL ? Optional.of(ValueVerdict.Kind.CRITICAL) : Optional.empty();
    }

    /**
     * How many of these values are drawn outside the printed range (OUTSIDE or CRITICAL by {@link #kindOf}): the header's
     * counts line (s10), counted by the rendered kind, so a row and the count can never disagree.
     */
    public static long outsideCount(List<Map<String, Object>> values) {
        return (values == null ? List.<Map<String, Object>>of() : values).stream().map(PaperDisplay::kindOf)
                .filter(k -> k == ValueVerdict.Kind.OUTSIDE || k == ValueVerdict.Kind.CRITICAL).count();
    }
}
