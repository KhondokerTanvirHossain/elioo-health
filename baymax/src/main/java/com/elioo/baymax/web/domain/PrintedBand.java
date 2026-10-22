package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.PrintedRange;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The range a paper PRINTED beside one reading (PO ruling R3, 2026-10-22, DR-48): read only off the stored extraction's
 * {@code ref_low} and {@code ref_high} for that observation, never from a reference table, never computed. Pure and
 * static. Whether a point may DRAW it is {@link PaperDisplay#drawnBand}: it must also agree with the point's chip.
 *
 * <ul>
 *   <li>{@code text} is what is stored, each side as stored (only the whitespace around a cell is dropped), the two
 *       sides joined by Java with an en dash ({@link #JOIN}) when both were stored — the form
 *       {@code line.value_outside_range} prints them in. It is NOT a transcription of the paper: the extraction keeps
 *       no raw printed cell, so a paper's "59 - 104" stored in two columns arrives as "59–104", and the same range
 *       stored in one column arrives as stored ("3.9-6.1"). Null when neither side holds any text.</li>
 *   <li>Two sides stored: {@code low} and {@code high} are numbers only when each is one plain number, optionally behind
 *       a bound sign facing its own column's way ({@code >40} as a low, {@code <200} as a high), and low &lt; high. A
 *       sign facing against its column contradicts the other column and is refused.</li>
 *   <li>One side stored: a lone SIGNED bound is placed by its sign, whichever column holds it ({@code <200} is a high of
 *       200 even stored as a low), because the extraction prompt and schema give no rule for which column a one-sided
 *       range goes in and the sign is what the paper printed. A lone BARE number is refused: only the unchecked column
 *       would say its side, and {@code <200} stored as a bare low {@code 200} would draw a band above 200.</li>
 *   <li>Anything else — a whole range in one column ({@code 3.9-6.1}), words ({@code Up to 200}, {@code Negative}), a
 *       unit, an ambiguous comma ({@code 1,23}, refused by {@link PrintedRange#number}), or an inverted or empty range —
 *       sends both numbers null and keeps the text. One unplaceable side spoils the other: half of a two-sided range
 *       drawn alone would misstate the paper.</li>
 * </ul>
 * Null is the safe answer and is used freely: a band not drawn hides nothing the paper said, a band drawn wrong puts a
 * reading inside a range the paper never printed.
 */
public record PrintedBand(Double low, Double high, String text) {

    /** No printed range: all three null, never a default. */
    public static final PrintedBand NONE = new PrintedBand(null, null, null);

    /** The en dash Java joins the two stored sides with, as {@code line.value_outside_range} joins them. */
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
        if (low == null || high == null) {
            return lone(low == null ? high : low);
        }
        String text = low + JOIN + high;
        Optional<Double> lowNumber = bound(low, LOW_SIGNS);
        Optional<Double> highNumber = bound(high, HIGH_SIGNS);
        if (lowNumber.isEmpty() || highNumber.isEmpty() || !(lowNumber.get() < highNumber.get())) {
            return new PrintedBand(null, null, text);
        }
        return new PrintedBand(lowNumber.get(), highNumber.get(), text);
    }

    /** The same text with no number: what a point sends when it may not draw this range. */
    public PrintedBand textOnly() {
        return new PrintedBand(null, null, text);
    }

    /** One stored side alone: placed by its sign, or not at all. */
    private static PrintedBand lone(String cell) {
        Matcher m = BOUND.matcher(PrintedRange.foldDigits(cell));
        if (!m.matches() || m.group(1) == null) {
            return new PrintedBand(null, null, cell);
        }
        Optional<Double> number = plain(m.group(2));
        if (number.isEmpty()) {
            return new PrintedBand(null, null, cell);
        }
        return LOW_SIGNS.contains(m.group(1)) ? new PrintedBand(number.get(), null, cell) : new PrintedBand(null, number.get(), cell);
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
        return plain(m.group(2));
    }

    /** The number itself, by {@link PrintedRange#number} (which refuses an ambiguous comma). */
    private static Optional<Double> plain(String number) {
        return PrintedRange.number(number.startsWith("+") ? number.substring(1) : number);
    }
}
