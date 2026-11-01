package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.domain.PaperDisplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The words a চ্যাট context labels its facts with, in the ask's language, read from the bundles the paper page reads: a
 * Bangla ask is given Bangla labels and an English ask English ones, so the model has no English label to copy into a
 * Bangla answer (the live Sonnet test of #164 found "printed range" and "OUTSIDE high" copied word for word). No label is
 * written here; each is the screen's own copy:
 * <ul>
 *   <li>the printed range: {@code value.range.one} ("রিপোর্টে লেখা সীমা: {0}"), or {@code value.range.none};</li>
 *   <li>the verdict: the chip the paper page draws for the reading, {@link PaperDisplay#chipKey} over its kind and stored
 *       side ({@code value.chip.*}), without the chip's drawn arrow; a sideless OUTSIDE or CRITICAL, which
 *       {@link PaperDisplay#kindOf} never yields, gets the UNKNOWN chip, as the page's own rule would;</li>
 *   <li>the paper's date: {@code timeline.paper} before it, or {@code timeline.sum.nodate} without one;</li>
 *   <li>the paired blood-pressure readings' name: {@code health.bp}, as the health screen names them.</li>
 * </ul>
 * The tags that start a fact line ({@link #TAGS}) are structure for the model, never labels, and the prompt says so.
 *
 * <p>{@link #foreign} is what an answer in this language may not hold: for a Bangla ask, every English label above, every tag
 * and the English words the prompt's rules use ({@link #RULE_WORDS}). {@link AnswerCheck} refuses a Bangla answer holding one
 * ({@code foreign_label}), which makes it a repair, never a silent rewrite. An English ask has none: its context and its
 * rules are English, and an English word is not foreign there.
 *
 * @param lang       {@code "bn"} or {@code "en"}
 * @param rangeOne   {@code value.range.one} with its {@code {0}} in place
 * @param rangeNone  {@code value.range.none}
 * @param verdicts   chip key to its label, arrow removed
 * @param paper      {@code timeline.paper}
 * @param undated    {@code timeline.sum.nodate}
 * @param bloodPressure {@code health.bp}
 * @param foreign    the phrases an answer in {@code lang} may not hold, in any case
 */
public record ChatLabels(String lang, String rangeOne, String rangeNone, Map<String, String> verdicts, String paper, String undated,
                         String bloodPressure, Set<String> foreign) {

    /** The tags a fact line starts with, and the user message's two headings: structure, never words for the family. */
    public static final List<String> TAGS = List.of("PATIENT", "VALUE", "PRESCRIPTION", "NOT SHOWN", "FACTS", "QUESTION");

    /** The English words of the prompt's own rules that name a label; a Bangla answer may not copy them either. */
    public static final List<String> RULE_WORDS = List.of("printed range", "INSIDE", "OUTSIDE", "UNKNOWN");

    static final String UNKNOWN_KEY = "value.chip.unknown";
    static final List<String> VERDICT_KEYS = List.of("value.chip.inside", "value.chip.outside.high", "value.chip.outside.low",
            "value.chip.critical.high", "value.chip.critical.low", UNKNOWN_KEY);

    /** Every bundle key this class reads, and nothing else. */
    public static final List<String> KEYS = keys();

    public ChatLabels {
        verdicts = Map.copyOf(verdicts);
        foreign = Set.copyOf(foreign);
    }

    /** The labels for an ask in {@code lang} ({@code "en"}, anything else is Bangla), read through the chat's copy port. */
    public static ChatLabels of(String lang, ChatCopyPort copy) {
        String l = "en".equals(lang) ? "en" : "bn";
        Set<String> foreign = new LinkedHashSet<>();
        if (!"en".equals(l)) {
            foreign.addAll(read("en", copy, Set.of()).phrases());
            foreign.addAll(TAGS);
            foreign.addAll(RULE_WORDS);
        }
        return read(l, copy, foreign);
    }

    private static ChatLabels read(String lang, ChatCopyPort copy, Set<String> foreign) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String key : VERDICT_KEYS) {
            verdicts.put(key, withoutArrow(copy.line(lang, key)));
        }
        return new ChatLabels(lang, copy.line(lang, "value.range.one"), copy.line(lang, "value.range.none"), verdicts,
                copy.line(lang, "timeline.paper"), copy.line(lang, "timeline.sum.nodate"), copy.line(lang, "health.bp"), foreign);
    }

    /** The range field: the printed range's text under its label, or the no-range label. */
    public String range(String printedText) {
        return printedText == null || printedText.isBlank() ? rangeNone : rangeOne.replace("{0}", printedText);
    }

    /** The verdict field: the chip the paper page draws for this kind and stored side. */
    public String verdict(ValueVerdict.Kind kind, String side) {
        ValueVerdict.Kind k = kind == null ? ValueVerdict.Kind.UNKNOWN : kind;
        String key = (k == ValueVerdict.Kind.OUTSIDE || k == ValueVerdict.Kind.CRITICAL) && side == null ? UNKNOWN_KEY
                : PaperDisplay.chipKey(k, side);
        return verdicts.getOrDefault(key, verdicts.get(UNKNOWN_KEY));
    }

    /** The paper field: its date under the paper label, or the no-date label. */
    public String paper(Object date) {
        return date == null ? undated : paper + " " + date;
    }

    /** The label alone of the range field, its placeholder and separator taken off ("রিপোর্টে লেখা সীমা"). */
    public String rangeLabel() {
        return rangeOne.replace("{0}", "").replaceAll("[\\s:：]+$", "").strip();
    }

    public String inside() {
        return verdicts.get("value.chip.inside");
    }

    public String unknown() {
        return verdicts.get(UNKNOWN_KEY);
    }

    /** The outside verdicts, in {@link #VERDICT_KEYS} order. */
    public List<String> outside() {
        List<String> out = new ArrayList<>();
        for (String key : VERDICT_KEYS) {
            if (key.contains(".outside.") || key.contains(".critical.")) {
                out.add(verdicts.get(key));
            }
        }
        return out;
    }

    /** Every label this context may write, each once: what a foreign set is made of. */
    public List<String> phrases() {
        Set<String> out = new LinkedHashSet<>();
        out.add(rangeLabel());
        out.add(rangeNone);
        for (String key : VERDICT_KEYS) {
            out.add(verdicts.get(key));
        }
        out.add(paper);
        out.add(undated);
        out.add(bloodPressure);
        return new ArrayList<>(out);
    }

    /** A chip's text without its drawn arrow ("▲ সীমার বেশি" is "সীমার বেশি"): the arrow is a picture, not a word. */
    static String withoutArrow(String chip) {
        return chip.replaceFirst("^[\\p{So}\\s]+", "");
    }

    private static List<String> keys() {
        List<String> k = new ArrayList<>(List.of("value.range.one", "value.range.none"));
        k.addAll(VERDICT_KEYS);
        k.addAll(List.of("timeline.paper", "timeline.sum.nodate", "health.bp"));
        return List.copyOf(k);
    }
}
