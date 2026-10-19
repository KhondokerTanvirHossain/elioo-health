package com.elioo.baymax.outbound.domain;

import java.util.Map;
import java.util.Optional;

/**
 * A value against the range printed beside it, read by code and nothing else: the reading a cell states, and whether
 * it sits inside BOTH printed bounds. Pure and static, no configuration and no I/O, so the urgency rule
 * ({@code UrgencyService}) and the pages read one copy of it. The pages may call this; they may not call
 * {@code UrgencyService} (HexagonalArchitectureTest), which states levels.
 */
public final class PrintedRange {

    private PrintedRange() {
    }

    /** A comparison operator: the lab declined to give a number, so there is no reading to compare. */
    private static final java.util.regex.Pattern BOUNDED =
            java.util.regex.Pattern.compile("[<>\u2264\u2265]|<=|>=");

    /** The number itself, with optional sign, thousands groups and a decimal point or comma. */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)*");

    /**
     * The reading a cell states, or empty when it does not state one.
     *
     * <p>This used to delete every character except digits, {@code .} and {@code -} and parse what was left.
     * On batch 2 that gave the right answer every time — by luck, not by reading: {@code "80.30 L"},
     * {@code "> 89"}, {@code "6,100"} and {@code "08"} all happened to survive it. <b>The luck runs out on a
     * decimal comma:</b> a European {@code "1,5"} stripped to {@code "15"}, a tenfold error, silently, on a
     * number a family is shown and a threshold is applied to.</p>
     *
     * <p>Empty is the safe answer and is used freely. A value with no reading escalates nothing and is shown
     * as printed; a value read WRONGLY reaches a family as a number. So a bound ({@code "> 89"}), a range
     * ({@code "0-2"}), two numbers in one cell ({@code "130 / 80"}) and an ambiguous comma ({@code "1,23"} —
     * 1.23 or 123?) are all refused rather than guessed.</p>
     */
    public static Optional<Double> number(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = foldDigits(String.valueOf(raw));
        if (text.isBlank() || BOUNDED.matcher(text).find()) {
            return Optional.empty();
        }
        // A unit can carry digits ("10^3/uL"), and they are not part of the reading. The value is printed
        // first, so the unit is cut away before the number is looked for.
        text = text.replaceAll("\\b10\\s*\\^?\\s*\\d+\\s*/.*$", " ").trim();

        java.util.regex.Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String first = m.group();
        // A minus that is not the sign — "1.5-", "--2" — is a mark we do not understand, not a reading. The
        // number pattern never captures it, so it has to be caught on the surrounding text.
        int after = m.end();
        if ((after < text.length() && text.charAt(after) == '-')
                || (m.start() > 0 && text.charAt(m.start() - 1) == '-')) {
            return Optional.empty();
        }
        // Two separate numbers in one cell is a range or a pair (a blood pressure), not a reading. A second
        // match that is not simply the same digits repeated means we cannot say which number is THE value.
        if (m.find()) {
            return Optional.empty();
        }
        // Anything left over that is a digit means the cut above missed something; refuse rather than read a
        // fragment. Letters and symbols (a flag "L", a unit, brackets) are fine and expected.
        String remainder = text.replace(first, " ");
        if (remainder.chars().anyMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return parseGrouped(first);
    }

    /**
     * Turns one matched number into a value, deciding what a comma means.
     *
     * <p>{@code "1,234"} is a thousands group; {@code "1,5"} is a decimal comma; {@code "1,23"} is genuinely
     * both — 1.23 in Europe, an impossible group of two digits elsewhere — and is refused. The rule is the
     * group's length: exactly three digits after every comma is a separator, one or more than three is a
     * decimal comma, and two is ambiguous.</p>
     */
    private static Optional<Double> parseGrouped(String token) {
        boolean negative = token.startsWith("-");
        String digits = negative ? token.substring(1) : token;
        if (digits.startsWith(".") || digits.startsWith(",") || digits.endsWith("-")) {
            return Optional.empty();
        }
        String[] parts = digits.split("[.,]", -1);
        if (parts.length > 1) {
            char separator = digits.charAt(digits.indexOf(parts[0]) + parts[0].length());
            String tail = parts[parts.length - 1];
            if (separator == ',' && parts.length == 2) {
                if (tail.length() == 2) {
                    return Optional.empty();                 // "1,23": decimal comma or bad grouping?
                }
                if (tail.length() != 3) {
                    digits = parts[0] + "." + tail;           // "1,5" is one and a half
                } else {
                    digits = parts[0] + tail;                // "6,100" is six thousand one hundred
                }
            } else if (separator == ',') {
                digits = String.join("", parts);             // "1,234,567"
            }
        }
        try {
            double value = Double.parseDouble(digits);
            return Optional.of(negative ? -value : value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * INSIDE by code (D35, and F2's INSIDE verdict): the value and BOTH printed bounds parse as one
     * reading each ({@link #number}) and low ≤ value ≤ high. A one-sided range ("<100"), a bound, a pair, an
     * ambiguous comma, a qualitative result or an inverted range is not inside — it is unknown, and unknown never
     * supports a reassurance. (An inverted range needs no clause of its own: no value satisfies low ≤ v ≤ high when
     * low > high.)
     */
    public static boolean insideByCode(Map<String, Object> v) {
        Optional<Double> value = number(v.get("value"));
        Optional<Double> low = number(v.get("ref_low"));
        Optional<Double> high = number(v.get("ref_high"));
        return value.isPresent() && low.isPresent() && high.isPresent()
                && value.get() >= low.get() && value.get() <= high.get();
    }

    /** Digits of any script to ASCII, so ৮.২ and 8.2 are one number. */
    public static String foldDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return sb.toString();
    }
}
