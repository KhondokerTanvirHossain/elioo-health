package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.TextNormaliser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DR-50 "Explain, never change", enforced in code. Two questions, both answered on {@link TextNormaliser}'s normal form,
 * never by the model:
 * <ul>
 *   <li>{@link #instructs}: whether a model's own words tell anyone to take, start, stop, skip or change a medicine or its
 *       dose (Bangla, Banglish, English). Run on the model's text after the stored prescription lines are stripped, so a
 *       line that IS the prescription ("খাবারের পরে খাবেন") is never held against it, and its own "অর্ধেক করে খান" always
 *       is. Any hit refuses the text; the model may not word a change at all, a refusal included ("ওষুধ বন্ধ করবেন কিনা,
 *       সেটা ডাক্তার বলবেন"): the refusal is code's job, {@code chat.dose.ask_doctor}.</li>
 *   <li>{@link #asksForChange}: whether the family's question asks for such a change, which makes code append
 *       {@code chat.dose.ask_doctor}. A change term in the question is enough; a bare "should I / করব / দেব" counts only
 *       beside a medicine word, so "can I see the last report?" is answered without it.</li>
 * </ul>
 * A term ending in {@code *} is a stem, matched from the start of a word ("বন্ধ কর*" is করুন, করবেন, করে দিন, করব);
 * every other term is a whole word or phrase, so "দেখান" is not "খান" and "কমাতে সাহায্য করে" is not "কমান".
 */
public final class DoseChangeGuard {

    /** The change instructions a model's text may never hold. */
    static final List<String> INSTRUCTIONS = List.of(
            // Bangla
            "খান", "খাবেন", "খাওয়ান", "খাওয়াবেন", "খেয়ে নিন", "খেতে পারেন", "খেতে থাকুন", "খেতে থাকবেন", "খাওয়া বন্ধ", "খাওয়া শুরু",
            "বন্ধ কর*", "বন্ধ রাখুন", "শুরু কর*", "বাদ দি*", "বাদ দে*", "বাড়ান", "বাড়াবেন", "বাড়াতে পারেন", "বাড়িয়ে দিন",
            "বাড়িয়ে দেবেন", "বাড়িয়ে নিন", "কমান", "কমাবেন", "কমাতে পারেন", "কমিয়ে দিন", "কমিয়ে দেবেন", "কমিয়ে নিন", "অর্ধেক",
            "দ্বিগুণ", "ডোজ বদল*", "ডোজ পরিবর্তন*", "ডোজ বাড়*", "ডোজ কম*", "চালিয়ে যান", "চালিয়ে যাবেন", "পাল্টে দিন", "পাল্টাবেন",
            // Banglish
            "kheye nin", "khaben", "khete paren", "bondho kor*", "shuru kor*", "bad din", "bad diben", "bariye*", "komiye*",
            "ordhek", "digun",
            // English
            "take it", "take the", "take them", "take one", "take two", "take half", "can take", "should take", "stop taking",
            "stop the", "stop it", "start taking", "start the", "skip*", "increase", "decrease", "reduce", "lower the dose",
            "raise the dose", "halve", "half", "double", "change the dose", "switch to", "cut the", "keep taking", "continue taking");

    /** Change words that only a question uses ("বাড়াব?"), each enough on its own. */
    static final List<String> QUESTION_CHANGES = List.of("বাড়াব", "বাড়াবো", "কমাব", "কমাবো", "পাল্টাব", "পাল্টাবো", "বাদ দেব", "বাদ দিব",
            "barabo", "komabo", "bad dibo", "bad debo");

    /** "Should I / may I" words, a dose question only beside a medicine word. */
    static final List<String> QUESTION_ASKS = List.of("করব", "করবো", "দেব", "দেবো", "দিব", "দিবো", "খাব", "খাবো", "খাওয়াব", "খাওয়াবো",
            "should i", "should she", "should he", "should we", "can i", "can we", "can she", "can he",
            "korbo", "dibo", "debo", "khabo", "khawabo");

    /** What a family calls a medicine or a dose, any script. */
    static final List<String> MEDICINE_WORDS = List.of("ওষুধ*", "ঔষধ*", "ঔষুধ*", "ডোজ*", "ট্যাবলেট*", "ট্যাব*", "ক্যাপসুল*",
            "ইনসুলিন*", "বড়ি*", "medicine*", "medication*", "meds", "dose*", "dosage*", "tablet*", "pill*", "insulin*", "capsule*",
            "osudh*", "oshudh*", "osud*", "oshud*", "oushod*");

    private DoseChangeGuard() {
    }

    /** The change instructions in a model's text, as {@code dose_change:<term>} codes, in list order; empty is a pass. */
    public static List<String> instructs(String modelText) {
        String hay = hay(modelText);
        Set<String> found = new LinkedHashSet<>();
        for (String term : INSTRUCTIONS) {
            if (hits(hay, term)) {
                found.add("dose_change:" + term.replace("*", ""));
            }
        }
        return new ArrayList<>(found);
    }

    /** Whether the family's question asks to start, stop, skip or change a medicine or its dose. */
    public static boolean asksForChange(String question) {
        String hay = hay(question);
        if (!instructs(question).isEmpty() || QUESTION_CHANGES.stream().anyMatch(t -> hits(hay, t))) {
            return true;
        }
        return QUESTION_ASKS.stream().anyMatch(t -> hits(hay, t)) && MEDICINE_WORDS.stream().anyMatch(t -> hits(hay, t));
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
