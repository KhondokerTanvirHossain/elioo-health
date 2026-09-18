package com.elioo.baymax.nudge.application.service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prescription's duration text to a number of days: "৭ দিন", "10 days", "১০", "2 weeks", "১ মাস". Bare numbers
 * are days. Anything else is empty — no nudge, logged by the caller. Digits of any script are folded first.
 */
final class DurationParser {

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern WEEKS = Pattern.compile("(?i)\\bweeks?\\b|\\bwks?\\b|সপ্তাহ");
    private static final Pattern MONTHS = Pattern.compile("(?i)\\bmonths?\\b|\\bmos?\\b|মাস");
    private static final Pattern DAYS = Pattern.compile("(?i)\\bdays?\\b|\\bd\\b|দিন");

    private DurationParser() {
    }

    static Optional<Integer> days(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String folded = fold(text.trim().toLowerCase(Locale.ROOT));
        Matcher m = NUMBER.matcher(folded);
        if (!m.find()) {
            return Optional.empty();
        }
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (n <= 0 || n > 3650) {
            return Optional.empty();
        }
        String rest = folded.substring(m.end());
        if (MONTHS.matcher(rest).find()) {
            return Optional.of(n * 30);
        }
        if (WEEKS.matcher(rest).find()) {
            return Optional.of(n * 7);
        }
        if (DAYS.matcher(rest).find() || rest.isBlank()) {
            return Optional.of(n);
        }
        return Optional.empty();
    }

    static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return sb.toString();
    }
}
