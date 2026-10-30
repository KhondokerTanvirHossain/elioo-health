package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.TextNormaliser;
import com.elioo.baymax.outbound.application.service.DocumentFacts;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checklist as code for a চ্যাট answer (DR-50), run on every model text before anything is shown. Returns violations as
 * {@code code} or {@code code:detail}; an empty list is the only pass. A detail can be patient text (a number, a term), so
 * a list reaches a log only through {@link #forLog}.
 *
 * <p>{@link #violations} checks the model's OWN words: each {@code {{rx:n}}} token is resolved against the stored
 * prescription lines and replaced by the word {@link DoseChangeGuard#RX_LINE}, so a stored line is exempt from every content
 * check (it is the paper, as DR-16 transcribes it), the model cannot smuggle a dose in by retyping one, and a verb or a
 * number written beside a line is still seen beside it. {@link #length} runs on the whole assembled text, the appended lines
 * included (CLAUDE.md: a limit is asserted on the thing sent).
 *
 * <p>Numbers (review of #164, findings 4, 11, 17, 18): the text is read in NFKC with every script's digits folded, so ½,
 * ² and ১ are numbers like 1. A whole date that matches a kept paper's (ISO, d/M/yyyy, "2 March 2026", "২ মার্চ") is taken
 * out first, as a stored line is; any other number must be in the closed set ({@link ChatContext#numbers}), which holds a
 * paper's year but never its day or month. A sentence that names a fact (its printed name or unit) may state only that
 * fact's numbers ({@code number_unbound}). A number written as a word, a fraction sign or a superscript is refused, as is
 * a dose amount ("10 mg", "2 tablets", "২টা") and any number in a sentence that holds a prescription line. "Inside" is
 * refused unless an INSIDE verdict backs it (finding 12).
 */
public final class AnswerCheck {

    /** One number as the family reads it: a decimal (with a point or a comma) is one number, never two. */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    static final Pattern REFERENCE = Pattern.compile("\\{\\{rx:(\\d+)}}");
    static final Pattern LEFTOVER = Pattern.compile("\\{\\{[^}]*}}|\\{\\{|}}");
    /** A number in a script's own number signs: a vulgar fraction, a superscript, a circled digit. */
    static final Pattern NUMBER_SIGN = Pattern.compile("\\p{No}");

    /** A dose amount: a number a dose unit follows, never a lab unit ("1.8 mg/dL" is not "1.8 mg"). */
    static final Pattern DOSE_AMOUNT = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?\\s*(?:mg|mcg|µg|μg|ml|iu|units?|tablets?|tabs?"
            + "|pills?|capsules?|caps?|drops?|puffs?|ট্যাবলেট|ট্যাব|টা|টি|টো|ইউনিট|এমজি|মিলি|মিগ্রা)(?![\\p{L}\\p{M}/])");

    /**
     * Number words, whole words only. Not "নয়": it is also the Bangla "is not", in nearly every sentence a refusal or a
     * printed-range comparison makes; a nine is written as a digit, which the closed set already governs. "একজন",
     * "একটু" and "একই" are not numbers either and are not listed; "এক", "একটা" and "একটি" are.
     */
    static final List<String> NUMBER_WORDS = numberWords();

    /** Comfort no paper states; whole phrases. */
    static final List<String> REASSURANCE = List.of("চিন্তার কিছু নেই", "ভয়ের কিছু নেই", "ভালো আছে", "স্বাভাবিক", "ঠিক আছে", "ঠিকঠাক",
            "গুরুতর কিছু না", "গুরুতর কিছু নয়", "গুরুতর কিছু নেই", "সমস্যা নেই", "সমস্যার কিছু নেই", "nothing to worry", "no need to worry",
            "fine", "normal", "ok", "okay", "all good", "nothing serious", "no problem", "no problems", "thik ache", "thik ase",
            "somossa nei", "chinta nei", "chintar kichu nei");

    /** What says a value is inside its range; allowed only where an INSIDE verdict backs it. */
    static final List<String> INSIDE_WORDS = List.of("inside", "within", "in range", "in the range", "ভিতরে", "ভেতরে", "সীমার মধ্যে",
            "সীমার ভিতর*", "সীমার ভেতর*", "এর মধ্যে", "রেঞ্জের মধ্যে", "সীমায়", "simar moddhe", "range er moddhe", "vitore", "bhitore");

    /** Month names, in NFKC and lower case, to their month: English in full and abbreviated, Bangla in full. */
    private static final Map<String, Integer> MONTHS = months();
    /** A Bangla month name may carry a case ending: "সেপ্টেম্বরের". */
    private static final List<String> BANGLA_ENDINGS = List.of("", "ের", "র", "এর", "ে");

    private static final Pattern ISO_DATE = Pattern.compile("(?<![\\d.,])(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?![\\d])");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\d.,])(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?![\\d])");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?<![\\d.,])(\\d{1,2})(?:st|nd|rd|th|ই|শে|লা|রা|ঠা)?\\s*(?:of\\s+)?([\\p{L}\\p{M}]+)\\.?(?:,?\\s*(\\d{4})(?![\\d.,]\\d))?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\d.,]?\\d)(?:,?\\s*(\\d{4})(?![\\d.,]\\d))?");

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
        if (own.replace(DoseChangeGuard.RX_LINE, " ").isBlank() && !REFERENCE.matcher(modelText).find()) {
            out.add("empty_body");
        }

        // numbers: stored names and units that carry digits ("HbA1c") are not numbers; a matching whole date is taken out
        String words = withoutStoredNames(own, ctx);
        Matcher sign = NUMBER_SIGN.matcher(words);
        while (sign.find()) {
            out.add("number_word:" + sign.group());
        }
        String dated = withoutStoredDates(DocumentFacts.fold(Normalizer.normalize(words, Normalizer.Form.NFKC)), ctx.dates());
        List<String> numberCodes = new ArrayList<>();
        List<String> boundCodes = new ArrayList<>();
        List<String> doseCodes = new ArrayList<>();
        List<String> insideCodes = new ArrayList<>();
        for (String sentence : DoseChangeGuard.sentences(dated)) {
            String hay = DoseChangeGuard.hay(sentence);
            List<String> numbers = new ArrayList<>();
            Matcher m = NUMBER.matcher(sentence);
            while (m.find()) {
                numbers.add(canonical(m.group()));
            }
            List<ChatContext.Fact> named = ctx.facts().stream().filter(f -> names(hay, f)).toList();
            for (String n : numbers) {
                if (!ctx.numbers().contains(n)) {
                    numberCodes.add("number_not_on_paper:" + n);
                } else if (!named.isEmpty() && named.stream().noneMatch(f -> f.numbers().contains(n))) {
                    boundCodes.add("number_unbound:" + n);
                }
            }
            if (DOSE_AMOUNT.matcher(sentence.toLowerCase(Locale.ROOT)).find()) {
                doseCodes.add("dose_change:amount");
            }
            if (DoseChangeGuard.hits(hay, DoseChangeGuard.RX_LINE) && !numbers.isEmpty()) {
                doseCodes.add("dose_change:number+rx");
            }
            for (String w : INSIDE_WORDS) {
                if (DoseChangeGuard.hits(hay, w) && !insideIsBacked(numbers, named, ctx)) {
                    insideCodes.add("inside_unverified:" + w.replace("*", ""));
                }
            }
        }
        out.addAll(numberCodes);
        out.addAll(boundCodes);

        String hay = DoseChangeGuard.hay(own);
        for (String w : NUMBER_WORDS) {
            if (DoseChangeGuard.hits(hay, w)) {
                out.add("number_word:" + w);
            }
        }
        out.addAll(doseCodes);
        out.addAll(DoseChangeGuard.instructs(own, ctx.medicineNames()));
        for (String r : REASSURANCE) {
            if (DoseChangeGuard.hits(hay, r)) {
                out.add("reassurance:" + r);
            }
        }
        out.addAll(insideCodes);
        return new ArrayList<>(out);
    }

    /** Whether a sentence names a fact: its printed name, or a unit with a letter in it, as a whole word or phrase. */
    private static boolean names(String hay, ChatContext.Fact f) {
        String name = key(f.name());
        String unit = key(f.unit());
        return (!name.isEmpty() && DoseChangeGuard.hits(hay, name))
                || (unit.length() >= 2 && unit.chars().anyMatch(Character::isLetter) && DoseChangeGuard.hits(hay, unit));
    }

    /**
     * An inside-word is backed when the context has an INSIDE fact, every fact the sentence names includes an INSIDE one,
     * and no number in the sentence belongs only to facts whose verdict is not INSIDE (a one-sided range is UNKNOWN).
     */
    private static boolean insideIsBacked(List<String> numbers, List<ChatContext.Fact> named, ChatContext ctx) {
        if (ctx.facts().stream().noneMatch(ChatContext.Fact::inside)) {
            return false;
        }
        if (!named.isEmpty() && named.stream().noneMatch(ChatContext.Fact::inside)) {
            return false;
        }
        for (String n : numbers) {
            List<ChatContext.Fact> holders = ctx.facts().stream().filter(f -> f.numbers().contains(n)).toList();
            if (!holders.isEmpty() && holders.stream().noneMatch(ChatContext.Fact::inside)) {
                return false;
            }
        }
        return true;
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

    /**
     * A paper's date as the one number the closed set holds for it: the year. Its day and month are accepted only inside a
     * whole date that matches it, never as free numbers (review of #164, findings 4, 11, 17).
     */
    public static Set<String> yearOf(LocalDate date) {
        return date == null ? Set.of() : Set.of(String.valueOf(date.getYear()));
    }

    /** The model's own words: references resolved for their check and replaced by {@link DoseChangeGuard#RX_LINE}. */
    private static String ownWords(String modelText, ChatContext ctx, Set<String> out) {
        Matcher m = REFERENCE.matcher(modelText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > ctx.verbatimLines().size()) {
                out.add("unresolved_reference:" + m.group());
            }
            m.appendReplacement(sb, " " + DoseChangeGuard.RX_LINE + " ");
        }
        m.appendTail(sb);
        String own = sb.toString();
        Matcher left = LEFTOVER.matcher(own);
        while (left.find()) {
            out.add("unresolved_reference:" + left.group());
        }
        return LEFTOVER.matcher(own).replaceAll(" ");
    }

    /**
     * The text with every stored fact name and unit that holds a digit ("HbA1c", "10^3/µL") written as its {@link #key}, any
     * case: its digits are part of a name, not a number, and the sentence still names the fact.
     */
    private static String withoutStoredNames(String text, ChatContext ctx) {
        String out = text;
        for (ChatContext.Fact f : ctx.facts()) {
            for (String s : List.of(f.name(), f.unit())) {
                if (!s.isBlank() && s.chars().anyMatch(Character::isDigit)) {
                    out = Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out)
                            .replaceAll(Matcher.quoteReplacement(" " + key(s) + " "));
                }
            }
        }
        return out;
    }

    /** A stored name or unit as a sentence is searched for it: normal form, digits dropped ("HbA1c" is "hbac"). */
    static String key(String nameOrUnit) {
        return TextNormaliser.normalise(nameOrUnit == null ? "" : nameOrUnit.replaceAll("\\p{N}+", ""));
    }

    /** The text with every whole date that matches a stored paper's date taken out; any other date is left to be checked. */
    static String withoutStoredDates(String text, Set<LocalDate> dates) {
        if (dates.isEmpty()) {
            return text;
        }
        String out = replaceDates(text, ISO_DATE, m -> matches(dates, parse(m.group(1)), parse(m.group(2)), parse(m.group(3))));
        out = replaceDates(out, NUMERIC_DATE, m -> {
            int y = parse(m.group(3));
            int year = y < 100 ? 2000 + y : y;
            return matches(dates, year, parse(m.group(2)), parse(m.group(1))) || matches(dates, year, parse(m.group(1)), parse(m.group(2)));
        });
        out = replaceDates(out, DAY_MONTH, m -> {
            Integer month = month(m.group(2));
            return month != null && matches(dates, m.group(3) == null ? null : parse(m.group(3)), month, parse(m.group(1)));
        });
        out = replaceDates(out, MONTH_DAY, m -> {
            Integer month = month(m.group(1));
            return month != null && matches(dates, m.group(3) == null ? null : parse(m.group(3)), month, parse(m.group(2)));
        });
        return out;
    }

    private static String replaceDates(String text, Pattern pattern, Function<Matcher, Boolean> stored) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(stored.apply(m) ? " " : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean matches(Set<LocalDate> dates, Integer year, int month, int day) {
        return dates.stream().anyMatch(d -> d.getMonthValue() == month && d.getDayOfMonth() == day && (year == null || d.getYear() == year));
    }

    private static int parse(String digits) {
        return Integer.parseInt(digits);
    }

    /** The month a word names, or null: an English name or abbreviation exactly, a Bangla name with a case ending. */
    private static Integer month(String word) {
        String w = Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Integer exact = MONTHS.get(w);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> e : MONTHS.entrySet()) {
            if (!e.getKey().isEmpty() && e.getKey().codePointAt(0) >= 0x0980 && w.startsWith(e.getKey())
                    && BANGLA_ENDINGS.contains(w.substring(e.getKey().length()))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Map<String, Integer> months() {
        Map<String, Integer> m = new LinkedHashMap<>();
        String[][] names = {
                {"january", "jan", "জানুয়ারি", "জানুয়ারী"}, {"february", "feb", "ফেব্রুয়ারি", "ফেব্রুয়ারী"}, {"march", "mar", "মার্চ"},
                {"april", "apr", "এপ্রিল"}, {"may", "মে"}, {"june", "jun", "জুন"}, {"july", "jul", "জুলাই"},
                {"august", "aug", "আগস্ট", "আগষ্ট"}, {"september", "sep", "sept", "সেপ্টেম্বর"}, {"october", "oct", "অক্টোবর"},
                {"november", "nov", "নভেম্বর"}, {"december", "dec", "ডিসেম্বর"}};
        for (int i = 0; i < names.length; i++) {
            for (String n : names[i]) {
                m.put(Normalizer.normalize(n, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), i + 1);
            }
        }
        return m;
    }

    private static List<String> numberWords() {
        List<String> w = new ArrayList<>(List.of(
                // Bangla: one to ten, the counted and the repeated forms, fractions and multiples
                "এক", "একটা", "একটি", "দুই", "দু", "দুটো", "দুটি", "দুইটা", "দুইটি", "তিন", "তিনটা", "তিনটি", "তিনটে", "চার", "চারটা",
                "চারটি", "পাঁচ", "পাঁচটা", "ছয়", "ছয়টা", "সাত", "আট", "দশ", "একবার", "দুবার", "দুইবার", "তিনবার", "একবেলা", "দুবেলা",
                "তিনবেলা", "দেড়", "আড়াই", "অর্ধেক", "সিকি", "গুণ", "দ্বিগুণ", "ভাগ", "শতাংশ", "প্রায়",
                // Bangla: hundreds, thousands, lakhs
                "শ", "শো", "শত", "একশ", "একশো", "একশত", "দুইশ", "দুইশো", "দুশো", "তিনশ", "তিনশো", "চারশো", "পাঁচশো", "হাজার*",
                "লাখ*", "লক্ষ*", "কোটি*",
                // Banglish
                "ek", "dui", "duita", "duto", "duti", "tin", "tinta", "char", "pach", "choy", "dosh", "ekta", "ekti", "ekbar", "dubar",
                "dubela", "tinbela", "der", "arai", "ordhek",
                // English
                "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
                "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty",
                "seventy", "eighty", "ninety", "hundred*", "thousand*", "lakh*", "crore*", "dozen*", "once", "twice", "thrice",
                "double", "half", "quarter", "triple", "times", "percent", "fold"));
        // Bangla eleven to ninety-nine, each written as one word; "ঊন-" is also typed "উন-"
        for (String n : List.of("এগারো", "বারো", "তেরো", "চৌদ্দ", "পনেরো", "ষোলো", "সতেরো", "আঠারো", "উনিশ", "বিশ", "কুড়ি", "একুশ", "বাইশ",
                "তেইশ", "চব্বিশ", "পঁচিশ", "ছাব্বিশ", "সাতাশ", "আটাশ", "ঊনত্রিশ", "ত্রিশ", "তিরিশ", "একত্রিশ", "বত্রিশ", "তেত্রিশ", "চৌত্রিশ",
                "পঁয়ত্রিশ", "ছত্রিশ", "সাঁইত্রিশ", "আটত্রিশ", "ঊনচল্লিশ", "চল্লিশ", "একচল্লিশ", "বিয়াল্লিশ", "তেতাল্লিশ", "চুয়াল্লিশ",
                "পঁয়তাল্লিশ", "ছেচল্লিশ", "সাতচল্লিশ", "আটচল্লিশ", "ঊনপঞ্চাশ", "পঞ্চাশ", "একান্ন", "বায়ান্ন", "তিপ্পান্ন", "চুয়ান্ন",
                "পঞ্চান্ন", "ছাপ্পান্ন", "সাতান্ন", "আটান্ন", "ঊনষাট", "ষাট", "একষট্টি", "বাষট্টি", "তেষট্টি", "চৌষট্টি", "পঁয়ষট্টি",
                "ছেষট্টি", "সাতষট্টি", "আটষট্টি", "ঊনসত্তর", "সত্তর", "একাত্তর", "বাহাত্তর", "তিয়াত্তর", "চুয়াত্তর", "পঁচাত্তর",
                "ছিয়াত্তর", "সাতাত্তর", "আটাত্তর", "ঊনআশি", "আশি", "একাশি", "বিরাশি", "তিরাশি", "চুরাশি", "পঁচাশি", "ছিয়াশি",
                "সাতাশি", "অষ্টআশি", "ঊননব্বই", "নব্বই", "একানব্বই", "বিরানব্বই", "তিরানব্বই", "চুরানব্বই", "পঁচানব্বই",
                "ছিয়ানব্বই", "সাতানব্বই", "আটানব্বই", "নিরানব্বই")) {
            w.add(n);
            if (n.startsWith("ঊন")) {
                w.add("উন" + n.substring(2));
            }
        }
        return List.copyOf(w);
    }
}
