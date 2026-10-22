package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.PrintedRange;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The range a paper PRINTED beside one reading, as the trend's pale band may draw it (PO ruling R3, 2026-10-22): read
 * only off the stored extraction's {@code ref_low} and {@code ref_high} for that observation, never from a reference
 * table, never computed. Pure and static.
 *
 * <ul>
 *   <li>{@code text} is what is stored, each side as stored (only the whitespace around a cell is dropped), the two
 *       sides joined with an en dash when both were printed — the form {@code line.value_outside_range} prints them in.
 *       Null when neither side holds any text.</li>
 *   <li>{@code low} and {@code high} are numbers only when every side that was printed is one plain number, optionally
 *       behind a bound sign facing its own way ({@code >40} as a low, {@code <200} as a high). A one-sided range carries
 *       only its side. Anything else — a whole range in one column ({@code 3.9-6.1}), words ({@code Up to 200},
 *       {@code Negative}), a unit, an ambiguous comma ({@code 1,23}, refused by {@link PrintedRange#number}), a sign
 *       facing the wrong way ({@code <200} as a low would draw a band above 200), or an inverted or empty range — sends
 *       both numbers null and keeps the text. One unplaceable side spoils the other: half of a two-sided range drawn
 *       alone would misstate the paper.</li>
 * </ul>
 * Null is the safe answer and is used freely: a band not drawn hides nothing the paper said, a band drawn wrong puts a
 * reading inside a range the paper never printed.
 */
public record PrintedBand(Double low, Double high, String text) {

    /** No printed range: all three null, never a default. */
    public static final PrintedBand NONE = new PrintedBand(null, null, null);

    /** The en dash the two printed sides are joined with, as {@code line.value_outside_range} joins them. */
    static final String JOIN = "–";

    /** An optional bound sign, then one number (sign, digits, groups or a decimal), nothing else. */
    private static final Pattern BOUND = Pattern.compile("^(<=|>=|<|>|≤|≥)?\\s*([+-]?\\d+(?:[.,]\\d+)*)$");
    private static final Set<String> LOW_SIGNS = Set.of(">", ">=", "≥");
    private static final Set<String> HIGH_SIGNS = Set.of("<", "<=", "≤");

    public static PrintedBand of(Object refLow, Object refHigh) {
        String low = cell(refLow);
        String high = cell(refHigh);
        if (low == null && high == null) {
            return NONE;
        }
        String text = low == null ? high : high == null ? low : low + JOIN + high;
        Optional<Double> lowNumber = low == null ? Optional.empty() : bound(low, LOW_SIGNS);
        Optional<Double> highNumber = high == null ? Optional.empty() : bound(high, HIGH_SIGNS);
        boolean placed = (low == null || lowNumber.isPresent()) && (high == null || highNumber.isPresent());
        if (placed && lowNumber.isPresent() && highNumber.isPresent() && !(lowNumber.get() < highNumber.get())) {
            placed = false;
        }
        return placed ? new PrintedBand(lowNumber.orElse(null), highNumber.orElse(null), text) : new PrintedBand(null, null, text);
    }

    /** The stored cell without the whitespace around it, or null when it holds no text. */
    private static String cell(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).strip();
        return s.isEmpty() ? null : s;
    }

    /** One plain number, behind no sign or a sign of {@code allowed}; empty for anything else. */
    private static Optional<Double> bound(String cell, Set<String> allowed) {
        Matcher m = BOUND.matcher(PrintedRange.foldDigits(cell));
        if (!m.matches() || (m.group(1) != null && !allowed.contains(m.group(1)))) {
            return Optional.empty();
        }
        String number = m.group(2);
        return PrintedRange.number(number.startsWith("+") ? number.substring(1) : number);
    }
}
