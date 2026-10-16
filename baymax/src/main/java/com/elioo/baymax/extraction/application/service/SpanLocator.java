package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds an item's own text on the OCR page, because the character offsets a model returns are not to be
 * trusted (BMX-5b): two models placed the same phrase 190 characters apart, and drift grows down the page,
 * so the last line of a prescription — where the follow-up lives — is the one most often lost.
 *
 * <p>Two questions, both answered on normalised text (digits of any script folded to ASCII, case and
 * punctuation removed, whitespace collapsed) with a map back to the original offsets:
 * <ul>
 *   <li>{@link #locate}: where does this text sit on the page? Exact match nearest the claimed offset first,
 *       then a fuzzy token-window match at ≥ {@value #MIN_SIMILARITY} similarity.</li>
 *   <li>{@link #contains}: do these words actually carry this text? This is the invariant behind every
 *       stored crop: a crop that does not contain its item's text is never stored, whatever the model said.</li>
 * </ul>
 */
public final class SpanLocator {

    static final double MIN_SIMILARITY = 0.80;

    private SpanLocator() {
    }

    /** A located run of the page text, in original character offsets. */
    public record Range(int start, int end) {
    }

    /** Normalised text with, for each normalised char, the original offset it came from. */
    record Normalized(String text, int[] origin) {

        static Normalized of(String raw) {
            StringBuilder sb = new StringBuilder();
            List<Integer> map = new ArrayList<>();
            boolean pendingSpace = false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                char out;
                if (Character.isDigit(c)) {
                    out = (char) ('0' + Character.digit(c, 10));
                } else if (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK
                        || Character.getType(c) == Character.COMBINING_SPACING_MARK) {
                    out = Character.toLowerCase(c);
                } else {
                    pendingSpace = !sb.isEmpty();
                    continue;
                }
                if (pendingSpace) {
                    sb.append(' ');
                    map.add(i);
                    pendingSpace = false;
                }
                sb.append(out);
                map.add(i);
            }
            return new Normalized(sb.toString(), map.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    public static String normalize(String raw) {
        return raw == null ? "" : Normalized.of(raw).text();
    }

    /**
     * Where {@code anchor} sits on the page. Exact occurrences win; among several, the one nearest the
     * claimed span. Otherwise the best fuzzy window of the same token count (±1) at or above the threshold.
     */
    public static Optional<Range> locate(PageOcr page, ExtractionResult.SourceSpan claimed, String anchor) {
        String want = normalize(anchor);
        if (want.isBlank() || page.text() == null || page.text().isBlank()) {
            return Optional.empty();
        }
        Normalized norm = Normalized.of(page.text());
        int claimedStart = claimed == null ? 0 : Math.max(0, claimed.start());

        // 1. exact, nearest the claim
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int at = norm.text().indexOf(want); at >= 0; at = norm.text().indexOf(want, at + 1)) {
            int distance = Math.abs(norm.origin()[at] - claimedStart);
            if (distance < bestDistance) {
                best = at;
                bestDistance = distance;
            }
        }
        if (best >= 0) {
            return Optional.of(new Range(norm.origin()[best], norm.origin()[best + want.length() - 1] + 1));
        }

        // 2. fuzzy: token windows
        String[] wantTokens = want.split(" ");
        List<int[]> tokens = tokenSpans(norm.text());
        double bestScore = 0;
        int bestFrom = -1, bestTo = -1;
        for (int size = Math.max(1, wantTokens.length - 1); size <= wantTokens.length + 1; size++) {
            for (int i = 0; i + size <= tokens.size(); i++) {
                String window = norm.text().substring(tokens.get(i)[0], tokens.get(i + size - 1)[1]);
                double score = similarity(want, window);
                if (score >= MIN_SIMILARITY) {
                    int distance = Math.abs(norm.origin()[tokens.get(i)[0]] - claimedStart);
                    // higher score wins; equal score → nearer the claim wins
                    if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) < 1e-9 && bestFrom >= 0
                            && distance < Math.abs(norm.origin()[tokens.get(bestFrom)[0]] - claimedStart))) {
                        bestScore = score;
                        bestFrom = i;
                        bestTo = i + size - 1;
                    }
                }
            }
        }
        if (bestFrom < 0) {
            return Optional.empty();
        }
        return Optional.of(new Range(norm.origin()[tokens.get(bestFrom)[0]], norm.origin()[tokens.get(bestTo)[1] - 1] + 1));
    }

    /**
     * A reading whose name and value are not adjacent on the page — {@code BP : 130 / 80} for the diastolic
     * {@code BP 80}. Finds {@code value} on an OCR line (a Vision paragraph, newline-delimited) that also
     * carries {@code name}, nearest the claimed offset. Returns the whole line, so the crop shows the reading
     * in its context rather than a bare number.
     */

    /**
     * Whether {@code value} appears on {@code line} as a value in its own right, both already normalised.
     *
     * <p>Bounded by DIGITS, not by spaces. The space-delimited rule rejected every value printed with
     * something attached — "2.1 L" in one cell, "80.30 L", a value in brackets — and twelve values across
     * batch 2 lost their crops because of it, all of them on the page.
     *
     * <p>A prefix or substring match would be worse than the bug it fixes: "2.1" would hit "12.1", "2.15"
     * and "0.21", crop THAT row and store it as this value's source. A wrong crop is not a missing crop — it
     * is a number certified by a picture of a different number, which is the defect DR-12 exists to prevent.
     * So the only forbidden neighbours are a digit and a decimal point; a unit, a flag letter, a bracket or
     * nothing at all may sit against it.
     */
    static boolean valueAppearsOn(String rawLine, String value) {
        if (rawLine == null || value == null) {
            return false;
        }
        Normalized line = Normalized.of(rawLine);
        String want = normalize(value);
        if (want.isBlank() || line.text().isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = line.text().indexOf(want, from);
            if (at < 0) {
                return false;
            }
            // Boundaries are checked on the RAW text, because normalisation turns a decimal point into a
            // space: "2.1.3" becomes "2 1 3", where "2 1" sits between spaces and looks like a clean match.
            int rawStart = line.origin()[at];
            int rawEnd = line.origin()[at + want.length() - 1];
            if (!continuesNumber(rawLine, rawStart - 1) && !continuesNumber(rawLine, rawEnd + 1)) {
                return true;
            }
            from = at + 1;
        }
    }

    /** True when the character at {@code index} would make the match part of a larger number. */
    private static boolean continuesNumber(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        char c = text.charAt(index);
        return Character.isDigit(c) || c == '.' || c == ',';
    }

    public static Optional<Range> locateValueOnNamedLine(PageOcr page, ExtractionResult.SourceSpan claimed,
                                                          String name, String value) {
        String wantName = normalize(name), wantValue = normalize(value);
        if (wantValue.isBlank() || page.text() == null) {
            return Optional.empty();
        }
        int claimedStart = claimed == null ? 0 : Math.max(0, claimed.start());
        Range best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Range line : lines(page.text())) {
            String text = normalize(page.text().substring(line.start(), line.end()));
            boolean hasValue = valueAppearsOn(text, wantValue);
            boolean hasName = wantName.isBlank() || text.contains(wantName);
            if (hasValue && hasName) {
                int distance = Math.abs(line.start() - claimedStart);
                if (distance < bestDistance) {
                    best = line;
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The OCR line (newline-delimited run of the page text) containing an offset, or the range itself. */
    public static Range lineAround(PageOcr page, Range range) {
        for (Range line : lines(page.text())) {
            if (range.start() >= line.start() && range.start() < line.end()) {
                return new Range(line.start(), Math.max(line.end(), range.end()));
            }
        }
        return range;
    }

    static List<Range> lines(String text) {
        List<Range> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                if (i > start) {
                    out.add(new Range(start, i));
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** True when the words, read in order, carry {@code anchor} exactly or at ≥ threshold similarity. */
    public static boolean contains(List<PageOcr.PositionedWord> words, String anchor) {
        String want = normalize(anchor);
        if (want.isBlank()) {
            return false;
        }
        StringBuilder joined = new StringBuilder();
        for (PageOcr.PositionedWord w : words) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(w.text());
        }
        String have = normalize(joined.toString());
        if (have.contains(want)) {
            return true;
        }
        String[] wantTokens = want.split(" ");
        List<int[]> tokens = tokenSpans(have);
        for (int size = Math.max(1, wantTokens.length - 1); size <= wantTokens.length + 1; size++) {
            for (int i = 0; i + size <= tokens.size(); i++) {
                if (similarity(want, have.substring(tokens.get(i)[0], tokens.get(i + size - 1)[1])) >= MIN_SIMILARITY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<int[]> tokenSpans(String text) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < text.length() && text.charAt(i) != ' ') {
                i++;
            }
            if (i > start) {
                out.add(new int[]{start, i});
            }
        }
        return out;
    }

    /** 1 - levenshtein / max length; 1.0 for identical strings. */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
