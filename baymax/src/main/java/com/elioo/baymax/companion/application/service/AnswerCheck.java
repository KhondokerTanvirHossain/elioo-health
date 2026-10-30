package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.application.service.DocumentFacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checklist as code for a চ্যাট answer (DR-50), run on every model text before anything is shown. Returns violations as
 * {@code code} or {@code code:detail}; an empty list is the only pass. A detail can be patient text (a number, a term), so
 * a list reaches a log only through {@link #forLog}.
 *
 * <p>{@link #violations} checks the model's OWN words: each {@code {{rx:n}}} token is resolved against the stored
 * prescription lines and then stripped, so a stored line is exempt from every content check (it is the paper, as DR-16
 * transcribes it) and the model cannot smuggle a dose in by retyping one. {@link #length} runs on the whole assembled
 * text, the appended lines included (CLAUDE.md: a limit is asserted on the thing sent).
 */
public final class AnswerCheck {

    /** One number as the family reads it: a decimal (with a point or a comma) is one number, never two. */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    static final Pattern REFERENCE = Pattern.compile("\\{\\{rx:(\\d+)}}");
    static final Pattern LEFTOVER = Pattern.compile("\\{\\{[^}]*}}|\\{\\{|}}");

    /**
     * Number words, whole words only. Not "নয়": it is also the Bangla "is not", in nearly every sentence a refusal or a
     * printed-range comparison makes; a nine is written as a digit, which the closed set already governs.
     */
    static final List<String> NUMBER_WORDS = List.of("দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "দশ", "দেড়", "আড়াই", "অর্ধেক",
            "গুণ", "দ্বিগুণ", "ভাগ", "শতাংশ", "প্রায়", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twice",
            "double", "half", "triple", "times", "percent", "fold");

    /** Comfort no paper states; whole phrases. */
    static final List<String> REASSURANCE = List.of("চিন্তার কিছু নেই", "ভয়ের কিছু নেই", "ভালো আছে", "স্বাভাবিক", "nothing to worry",
            "no need to worry", "fine", "normal");

    /** Codes whose detail is a size, never patient text: the only details the log keeps. */
    private static final Set<String> LOGGABLE_DETAIL = Set.of("too_long");

    private AnswerCheck() {
    }

    /** The content violations of the model's text, in a fixed order, each once. */
    public static List<String> violations(String modelText, ChatContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        if (modelText == null || modelText.isBlank()) {
            out.add("empty_body");
            return new ArrayList<>(out);
        }
        String own = ownWords(modelText, ctx, out);
        if (own.isBlank() && !REFERENCE.matcher(modelText).find()) {
            out.add("empty_body");
        }
        Matcher m = NUMBER.matcher(DocumentFacts.fold(own));
        while (m.find()) {
            String n = canonical(m.group());
            if (!ctx.numbers().contains(n)) {
                out.add("number_not_on_paper:" + n);
            }
        }
        String hay = DoseChangeGuard.hay(own);
        for (String w : NUMBER_WORDS) {
            if (DoseChangeGuard.hits(hay, w)) {
                out.add("number_word:" + w);
            }
        }
        out.addAll(DoseChangeGuard.instructs(own));
        for (String r : REASSURANCE) {
            if (DoseChangeGuard.hits(hay, r)) {
                out.add("reassurance:" + r);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The text with every resolvable {@code {{rx:n}}} replaced by its stored line: what the family is shown as the body.
     * An unresolvable token is left as written; {@link #violations} refuses such a text before it is ever resolved.
     */
    public static String resolve(String modelText, ChatContext ctx) {
        Matcher m = REFERENCE.matcher(modelText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String line = n >= 1 && n <= ctx.verbatimLines().size() ? ctx.verbatimLines().get(n - 1) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(line));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The whole final text against the cap: {@code too_long:<length>} when it is over. */
    public static List<String> length(String finalText, int cap) {
        int n = finalText == null ? 0 : finalText.length();
        return n > cap ? List.of("too_long:" + n) : List.of();
    }

    /** Every code, and a detail only where it is a size: an allow-list, so a later code with patient text is cut by default. */
    public static List<String> forLog(List<String> violations) {
        return violations.stream().map(v -> {
            int colon = v.indexOf(':');
            return colon < 0 || LOGGABLE_DETAIL.contains(v.substring(0, colon)) ? v : v.substring(0, colon);
        }).toList();
    }

    /** A numeric token's canonical form: Bangla digits folded, a comma read as a point, trailing zeros dropped. */
    public static String canonical(String token) {
        String t = DocumentFacts.fold(token).replace(',', '.');
        return new BigDecimal(t).stripTrailingZeros().toPlainString();
    }

    /** The canonical numbers of every number in a stored text (a value, a printed range); empty for none. */
    public static Set<String> numbersIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(DocumentFacts.fold(text));
        while (m.find()) {
            out.add(canonical(m.group()));
        }
        return out;
    }

    /** A paper's date as the numbers a sentence may write it with: the year, the month and the day. */
    public static Set<String> dateNumbers(LocalDate date) {
        return date == null ? Set.of()
                : new LinkedHashSet<>(List.of(String.valueOf(date.getYear()), String.valueOf(date.getMonthValue()),
                String.valueOf(date.getDayOfMonth())));
    }

    /** The model's own words: references resolved for their check and stripped, and every leftover brace refused. */
    private static String ownWords(String modelText, ChatContext ctx, Set<String> out) {
        Matcher m = REFERENCE.matcher(modelText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > ctx.verbatimLines().size()) {
                out.add("unresolved_reference:" + m.group());
            }
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        String own = sb.toString();
        Matcher left = LEFTOVER.matcher(own);
        while (left.find()) {
            out.add("unresolved_reference:" + left.group());
        }
        return LEFTOVER.matcher(own).replaceAll(" ");
    }
}
