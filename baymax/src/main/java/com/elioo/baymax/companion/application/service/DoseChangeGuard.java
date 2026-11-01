package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.TextNormaliser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DR-50 "Explain, never change", enforced in code, and strict on the model's own words (review of #164, findings 1, 3, 9,
 * 16). The family sees a prescription line only as a code-inserted {@code {{rx:n}}} copy and the ask-the-doctor line only
 * as code's {@code chat.dose.ask_doctor}, so the model's own text never needs a medicine verb: the guard fails closed on
 * one. Two questions, both answered on {@link TextNormaliser}'s normal form, never by the model:
 * <ul>
 *   <li>{@link #instructs}: whether a model's own words could tell anyone to take, give, start, stop, skip, hold or change
 *       a medicine or its dose, in any polarity and any script. Run after every stored line is replaced by the word
 *       {@value #RX_LINE}, so the line itself ("খাবারের পরে খাবেন") is never held against the model, while a verb beside it
 *       ("Stop {{rx:1}} today") always is. Two lists:
 *       <ol>
 *         <li>{@link #INSTRUCTIONS}, refused anywhere: an instruction or a dose word that an explanation never needs
 *             (take, stop, start, keep, skip, dose, tablet, খান, নিন, দিন, বন্ধ, উচিত, লাগবে না…);</li>
 *         <li>{@link #NEAR_MEDICINE}, refused in a sentence that also refers to a medicine (the {@value #RX_LINE} word, a
 *             stored medicine's name, or a {@link #MEDICINE_WORDS} word): words harmless alone and an instruction beside a
 *             medicine (give, use, avoid, hold, need, tonight, সকালে, রাতে, আজ…).</li>
 *       </ol>
 *       Any hit refuses the text, a refusal included ("ওষুধ বন্ধ করবেন কিনা, সেটা ডাক্তার বলবেন"): the refusal is code's
 *       job.</li>
 *   <li>{@link #asksForChange}: whether the family's question asks for such a change, which makes code append
 *       {@code chat.dose.ask_doctor}. Any instruction above, a {@link #QUESTION_CHANGES} word, a {@link #QUESTION_VERBS}
 *       word with an ask-word or a question mark, or an ask-word or a {@link #MEDICINE_ONLY_CHANGES} word beside a
 *       medicine reference (a stored medicine's name counts: a family names the drug, not "the medicine").</li>
 * </ul>
 * A term ending in {@code *} is a stem, matched from the start of a word ("বন্ধ কর*" is করুন, করবেন, করে দিন, করব);
 * every other term is a whole word or phrase, so "দেখান" is not "খান", "given" is not "give" and "used" is not "use".
 */
public final class DoseChangeGuard {

    /** The word a stored prescription line is replaced with before any check of the model's own words. */
    public static final String RX_LINE = "rxline";

    /** Instructions and dose words a model's own text may never hold, anywhere. */
    static final List<String> INSTRUCTIONS = List.of(
            // Bangla
            "খান", "খাবেন", "খাওয়ান", "খাওয়াবেন", "খেয়ে নিন", "খেতে পারেন", "খেতে থাকুন", "খেতে থাকবেন", "খাওয়া বন্ধ", "খাওয়া শুরু",
            "বন্ধ কর*", "বন্ধ রাখ*", "বন্ধ থাক*", "বন্ধ", "শুরু কর*", "বাদ দি*", "বাদ দে*", "বাড়ান", "বাড়াবেন", "বাড়াতে পারেন",
            "বাড়িয়ে দিন", "বাড়িয়ে দেবেন", "বাড়িয়ে নিন", "কমান", "কমাবেন", "কমাতে পারেন", "কমিয়ে দিন", "কমিয়ে দেবেন", "কমিয়ে নিন",
            "অর্ধেক", "দ্বিগুণ", "ডোজ*", "চালিয়ে যা*", "পাল্টে দিন", "পাল্টাবেন",
            "নিন", "নেবেন", "নিবেন", "নিতে হবে", "নিতে পারেন", "নিতে থাকুন", "নিতে থাকবেন", "খেতে হবে", "খাওয়াতে হবে", "খাওয়া উচিত",
            "খাওয়া দরকার", "খাওয়ার দরকার", "দরকার নেই", "উচিত", "ছেড়ে দি*", "ছেড়ে দে*", "থামান", "থামাবেন", "থামিয়ে*", "লাগবে না",
            "দিন", "দেবেন", "দিবেন", "দিতে হবে", "দিয়ে দিন", "দিনে", "বেলা", "খালি পেটে", "ট্যাবলেট*", "ক্যাপসুল*",
            // Banglish
            "kheye nin", "khaben", "khete paren", "bondho kor*", "shuru kor*", "bad din", "bad diben", "bariye*", "komiye*",
            "ordhek", "digun", "nin", "neben", "niben", "khete hobe", "khawa uchit", "uchit", "chere*", "bondho rakh*", "bondho",
            "lagbe na", "chaliye ja*", "thaman", "deben", "diben", "din", "dorkar nei",
            // English
            "take", "takes", "taking", "took", "taken", "stop", "stops", "stopping", "stopped", "start", "starts", "starting",
            "started", "continu*", "keep", "keeps", "keeping", "resum*", "quit*", "discontinu*", "paus*", "skip*", "halve*", "half",
            "double*", "increas*", "decreas*", "reduc*", "lower the dose", "raise the dose", "switch*", "cut the", "cut it",
            "no need", "do not need", "does not need", "don t need", "doesn t need", "not needed", "no more", "any more", "anymore",
            "no longer", "safe to", "dose*", "dosage*", "tablet*", "pill*", "capsule*", "a day", "per day");

    /** Words refused only in a sentence that also refers to a medicine. */
    static final List<String> NEAR_MEDICINE = List.of(
            // Bangla
            "দিতে", "শুরু", "বাদ", "আর না", "দরকার", "লাগবে", "খাওয়া", "খাওয়ার", "খাওয়ানো", "চালু", "একসাথে", "আজ", "কাল", "এখন",
            "সকালে", "দুপুরে", "বিকেলে", "রাতে", "খাবারের পরে", "খাবারের আগে", "প্রতিদিন", "রোজ", "নিয়মিত",
            // Banglish
            "dite", "deya", "khawa", "aj", "kal", "rate e", "sokale",
            // English
            "give", "gives", "giving", "use", "uses", "using", "avoid*", "hold", "holds", "holding", "need", "needs", "must", "should",
            "today", "tonight", "tomorrow", "morning", "evening", "night", "daily", "days", "weeks", "now", "after food",
            "before food", "with food", "empty stomach", "bedtime");

    /** Change words that only a question uses ("বাড়াব?"), each enough on its own. */
    static final List<String> QUESTION_CHANGES = List.of("বাড়াব", "বাড়াবো", "কমাব", "কমাবো", "পাল্টাব", "পাল্টাবো", "বাদ দেব", "বাদ দিব",
            "barabo", "komabo", "bad dibo", "bad debo", "change the");

    /** A change verb in a question, a dose question with an ask-word or a question mark. */
    static final List<String> QUESTION_VERBS = List.of("change", "give", "নেব", "নেবো", "নিব", "খাওয়া যাবে", "দেওয়া যাবে", "ছাড়া যাবে",
            "chere", "nebo", "dibo");

    /** A change word that is a dose question only beside a medicine reference ("ইনসুলিন কি কম দেব?"). */
    static final List<String> MEDICINE_ONLY_CHANGES = List.of("কম", "বেশি", "kom", "beshi", "less", "more", "fewer", "extra");

    /** "Should I / may I" words, a dose question only beside a medicine reference. */
    static final List<String> QUESTION_ASKS = List.of("করব", "করবো", "দেব", "দেবো", "দিব", "দিবো", "খাব", "খাবো", "খাওয়াব", "খাওয়াবো",
            "should i", "should she", "should he", "should we", "can i", "can we", "can she", "can he", "is it ok", "is it okay",
            "korbo", "dibo", "debo", "khabo", "khawabo");

    /** What a family calls a medicine or a dose, any script. */
    static final List<String> MEDICINE_WORDS = List.of("ওষুধ*", "ঔষধ*", "ঔষুধ*", "ডোজ*", "ট্যাবলেট*", "ট্যাব*", "ক্যাপসুল*",
            "ইনসুলিন*", "বড়ি*", "medicine*", "medication*", "meds", "dose*", "dosage*", "tablet*", "pill*", "insulin*", "capsule*",
            "osudh*", "oshudh*", "osud*", "oshud*", "oushod*");

    /** A sentence break: a danda, ! ? ; or a line break, or a full stop that is not a decimal point. */
    private static final Pattern SENTENCE = Pattern.compile("[!?।;\\n]+|\\.(?!\\d)");

    private DoseChangeGuard() {
    }

    /** {@link #instructs(String, Set)} with no stored medicine names. */
    public static List<String> instructs(String modelText) {
        return instructs(modelText, Set.of());
    }

    /**
     * The change instructions in a model's own text, as {@code dose_change:<term>} codes, in list order; empty is a pass.
     * {@code medicineNames} are the stored medicines' names in normal form ({@code ChatContext.medicineNames}).
     */
    public static List<String> instructs(String modelText, Set<String> medicineNames) {
        String hay = hay(modelText);
        Set<String> found = new LinkedHashSet<>();
        for (String term : INSTRUCTIONS) {
            if (hits(hay, term)) {
                found.add("dose_change:" + term.replace("*", ""));
            }
        }
        for (String sentence : sentences(modelText)) {
            String s = hay(sentence);
            if (refersToMedicine(s, medicineNames)) {
                for (String term : NEAR_MEDICINE) {
                    if (hits(s, term)) {
                        found.add("dose_change:" + term.replace("*", "") + "+medicine");
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** {@link #asksForChange(String, Set)} with no stored medicine names. */
    public static boolean asksForChange(String question) {
        return asksForChange(question, Set.of());
    }

    /** Whether the family's question asks to start, stop, skip or change a medicine or its dose. */
    public static boolean asksForChange(String question, Set<String> medicineNames) {
        String hay = hay(question);
        if (!instructs(question, medicineNames).isEmpty() || QUESTION_CHANGES.stream().anyMatch(t -> hits(hay, t))) {
            return true;
        }
        boolean asks = QUESTION_ASKS.stream().anyMatch(t -> hits(hay, t));
        if ((asks || question.contains("?")) && QUESTION_VERBS.stream().anyMatch(t -> hits(hay, t))) {
            return true;
        }
        return refersToMedicine(hay, medicineNames) && (asks || MEDICINE_ONLY_CHANGES.stream().anyMatch(t -> hits(hay, t)));
    }

    /** Whether a text in {@link #hay} form refers to a medicine: a stored line, a stored medicine's name, or a medicine word. */
    static boolean refersToMedicine(String hay, Set<String> medicineNames) {
        if (hits(hay, RX_LINE) || MEDICINE_WORDS.stream().anyMatch(t -> hits(hay, t))) {
            return true;
        }
        return medicineNames != null && medicineNames.stream().anyMatch(n -> hits(hay, n));
    }

    /** The text's sentences, split before normalising, so a decimal point never splits one. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String s : SENTENCE.split(text)) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /** The text in normal form between two spaces, so every term can be matched against word breaks. */
    static String hay(String text) {
        return " " + TextNormaliser.normalise(text) + " ";
    }

    /** A whole word or phrase, or with a trailing {@code *} a stem from the start of a word. */
    static boolean hits(String hay, String term) {
        boolean stem = term.endsWith("*");
        String n = TextNormaliser.normalise(stem ? term.substring(0, term.length() - 1) : term);
        if (n.isEmpty()) {
            return false;
        }
        return stem ? hay.contains(" " + n) : hay.contains(" " + n + " ");
    }
}
