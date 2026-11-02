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
 *       ("Stop {{rx:1}} today") always is. Three lists:
 *       <ol>
 *         <li>{@link #INSTRUCTIONS}, refused anywhere: an instruction or a dose word that an explanation never needs
 *             (take, stop, start, keep, skip, dose, tablet, খান, নিন, দিন, বন্ধ, উচিত, লাগবে না…);</li>
 *         <li>{@link #NEAR_MEDICINE}, refused in a sentence that also refers to a medicine (the {@value #RX_LINE} word, a
 *             stored medicine's name, or a {@link #MEDICINE_WORDS} word): words harmless alone and an instruction beside a
 *             medicine (give, use, avoid, hold, need, change, tonight, সকালে, রাতে, আজ, বদল-, পাল্ট-…). A stored
 *             medicine's name is a stem and is also looked for with a hyphen inside a word dropped, so "Demoprole" and
 *             "Demo-prol" are the medicine. For P12's change words alone ({@link #CHANGE_WORDS}), in a sentence about a
 *             value ({@link #VALUE_WORDS}), a stored lab's name is the value, never the medicine it shares a word with;</li>
 *         <li>{@link #IMPERATIVES}, refused as a sentence's first word (change, drop, omit, adjust, taper…), and
 *             {@link #IMPERATIVES_NEAR_MEDICINE} likewise when the sentence refers to a medicine (lower).</li>
 *       </ol>
 *       All three are blocklists, so a form no corpus row names can still pass: the reviews of #170 propose a fixed
 *       form to the PO instead (proposal C).
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
            // PO ruling P12, proposal (A) of #170, then the further forms of the same instruction found with it
            "বদলান", "বদলাবেন", "বদলে ফেলুন", "পাল্টান", "পাল্টে ফেলুন",
            "বদলিয়ে ফেলুন", "পাল্টিয়ে ফেলুন", "বদলাতে পারেন", "পাল্টাতে পারেন", "পরিবর্তন করুন", "পরিবর্তন করবেন",
            "পরিবর্তন করতে পারেন",
            // Banglish
            "kheye nin", "khaben", "khete paren", "bondho kor*", "shuru kor*", "bad din", "bad diben", "bariye*", "komiye*",
            "ordhek", "digun", "nin", "neben", "niben", "khete hobe", "khawa uchit", "uchit", "chere*", "bondho rakh*", "bondho",
            "lagbe na", "chaliye ja*", "thaman", "deben", "diben", "din", "dorkar nei",
            // English
            "take", "takes", "taking", "took", "taken", "stop", "stops", "stopping", "stopped", "start", "starts", "starting",
            "started", "continu*", "keep", "keeps", "keeping", "resum*", "quit*", "discontinu*", "paus*", "skip*", "halve*", "half",
            "double*", "increas*", "decreas*", "reduc*", "lower the dose", "raise the dose", "switch*", "cut the", "cut it",
            "no need", "do not need", "does not need", "don t need", "doesn t need", "not needed", "no more", "any more", "anymore",
            "no longer", "safe to", "dose*", "dosage*", "tablet*", "pill*", "capsule*", "a day", "per day",
            // P12: the pronoun form of a change, which names no medicine for NEAR_MEDICINE to see
            "change it",
            // Reviews of #170 (2026-11-01): instruction forms that passed every check at eb0d0fde, each a corpus flag row
            // first. Bangla: try-it, finish-it, can-be-done, must and familiar forms of a change; use, swap, stop, half
            "বদলে ফেল*", "পাল্টে ফেল*", "বদলিয়ে ফেল*", "পাল্টিয়ে ফেল*", "বদলে দেখ*", "পাল্টে দেখ*", "কমিয়ে দেখ*", "বাড়িয়ে দেখ*",
            "করে দেখ*", "বদলে দি*", "পাল্টে দি*", "দিতে পারেন", "বদলানো যা*", "পাল্টানো যা*", "পরিবর্তন করা যা*", "বদলানো যে*",
            "পাল্টানো যে*", "পরিবর্তন করা যে*", "বদলাতে হবে", "পাল্টাতে হবে", "পরিবর্তন করতে হবে", "বদলাও", "পাল্টাও",
            "বদলাতে পারবেন", "পাল্টাতে পারবেন", "পরিবর্তন করতে পারবেন", "বাদ রাখ*", "ব্যবহার করুন", "ব্যবহার করবেন",
            "ব্যবহার করতে পারেন", "ব্যবহার করতে হবে", "চলবে", "চলে যান", "এড়িয়ে চল*", "ছাড়া থাক*", "ছাড়া চল*", "স্থগিত*", "খেলেও", "খাওয়ালেও",
            "প্রয়োজন নেই", "প্রয়োজন নাই", "দরকার নাই", "কমিয়ে ফেল*", "কমিয়ে আন*", "কম কর*", "বাড়িয়ে ফেল*", "বেশি কর*", "আধা",
            "আধখানা", "আধাআধি",
            // Banglish
            "bodlan", "badlan", "paltan", "bodlao", "badlao", "paltao", "felun", "change kor*", "bad dao", "bad den", "bad dite",
            "bad rakh*", "dorkar nai", "dorkar nay", "proyojon nei", "proyojon nai", "adha", "nen",
            // English: "halving" is not "halve*"; the pronoun and passive forms of a change; try
            "halv*", "change them", "change that", "change this", "change to", "drop it", "replace it", "replacing it", "swap it",
            "swapping it", "substitute it", "be changed", "be replaced", "be swapped", "be substituted", "be dropped", "be adjusted",
            "try", "trying", "go without", "go easy");

    /**
     * Imperatives refused as the first word of a sentence, the medicine named or not ("Change Demo-prol.", a name spelled
     * otherwise than stored): an explanation never opens with one (reviews of #170).
     */
    static final List<String> IMPERATIVES = List.of("change", "drop", "ditch", "omit", "forget", "suspend", "withhold", "cease",
            "alter", "adjust", "modify", "tweak", "cut", "split", "lessen", "raise", "taper", "wean", "trade", "replace",
            "swap", "substitute", "halve");

    /** Imperatives refused as a sentence's first word only beside a medicine: "Lower than the range" is a lab's words. */
    static final List<String> IMPERATIVES_NEAR_MEDICINE = List.of("lower");

    /** Words refused only in a sentence that also refers to a medicine. */
    static final List<String> NEAR_MEDICINE = List.of(
            // Bangla
            "দিতে", "শুরু", "বাদ", "আর না", "দরকার", "লাগবে", "খাওয়া", "খাওয়ার", "খাওয়ানো", "চালু", "একসাথে", "আজ", "কাল", "এখন",
            "সকালে", "দুপুরে", "বিকেলে", "রাতে", "খাবারের পরে", "খাবারের আগে", "প্রতিদিন", "রোজ", "নিয়মিত",
            // PO ruling P12, proposal (A) of #170: a change word, harmless beside a lab value ("মান বদলেছে"), never beside a
            // medicine; then the formal word and the other romanizations found with it
            "বদল*", "পাল্ট*", "পরিবর্তন*",
            // Banglish
            "dite", "deya", "khawa", "aj", "kal", "rate e", "sokale",
            "badla*", "palt*", "badle*", "bodla*", "bodle*",
            // English
            "give", "gives", "giving", "use", "uses", "using", "avoid*", "hold", "holds", "holding", "need", "needs", "must", "should",
            "today", "tonight", "tomorrow", "morning", "evening", "night", "daily", "days", "weeks", "now", "after food",
            "before food", "with food", "empty stomach", "bedtime",
            "change", "changes", "changing", "changed", "replac*", "swap*", "substitut*",
            // Reviews of #170 (2026-11-01): stop, swap, reduce and need words beside a medicine, each a corpus flag row first
            "পরিবর্তে", "জায়গায়", "বাতিল*", "সেবন*", "ছাড়াই", "প্রয়োজন*", "জরুরি", "ভেঙে", "পর পর",
            "bodol*", "khan", "koma*", "kombe", "bara*", "jayga*", "jaiga*",
            "drop", "drops", "dropping", "dropped", "ditch*", "omit*", "forget*", "forgo*", "leave out", "suspend*", "withhold*",
            "ceas*", "off", "without", "requir*", "necessar*", "unnecessar*", "optional", "instead", "rather than", "alternativ*",
            "trade*", "move from", "alter*", "adjust*", "modif*", "tweak*", "cut", "cuts", "cutting", "split*", "lessen*", "less",
            "more", "can", "could", "might", "may be", "may not", "would", "taper*", "wean*", "every other", "easy");

    /**
     * P12's change words, the only ones that read a stored lab's name as the paper's value when the sentence is about a
     * value ({@link #VALUE_WORDS}): "Ma-র Vitamin D মান বদলেছে" is not a change to "Tab. Vitamin D3 Calcium". Every other
     * term, and every instruction form, still counts a lab name that is also a medicine's as the medicine.
     */
    static final Set<String> CHANGE_WORDS = Set.of("বদল*", "পাল্ট*", "পরিবর্তন*", "badla*", "palt*", "badle*", "bodla*", "bodle*",
            "bodol*", "change", "changes", "changing", "changed", "replac*", "swap*", "substitut*");

    /** What makes a sentence about a value on a paper, so a stored lab's name in it is that value (review 1 of #170, 3). */
    static final List<String> VALUE_WORDS = List.of("মান*", "রিপোর্ট*", "কাগজ*", "value*", "level*", "reading*", "result*", "report*",
            "paper*");

    /** Change words that only a question uses ("বাড়াব?"), each enough on its own. */
    static final List<String> QUESTION_CHANGES = List.of("বাড়াব", "বাড়াবো", "কমাব", "কমাবো", "পাল্টাব", "পাল্টাবো", "বদলাব", "বদলাবো",
            "বাদ দেব", "বাদ দিব",
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
            "osudh*", "oshudh*", "osud*", "oshud*", "oushod*", "brand*", "ব্র্যান্ড*");

    /** A hyphen inside a word ("Demo-prol"), dropped when a sentence is searched for a stored medicine's name. */
    private static final Pattern INNER_HYPHEN = Pattern.compile("(?<=[\\p{L}\\p{M}])[-‐‑–](?=[\\p{L}\\p{M}])");

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
        return instructs(modelText, medicineNames, Set.of());
    }

    /**
     * {@link #instructs(String, Set)}, with the stored labs' names in normal form ({@code "vitamin d"}): for a P12 change
     * word ({@link #CHANGE_WORDS}) only, in a sentence about a value ({@link #VALUE_WORDS}), a lab's name is the value and
     * not a medicine, even where it shares a word with a stored medicine's name or is a medicine word.
     */
    public static List<String> instructs(String modelText, Set<String> medicineNames, Set<String> labNames) {
        String hay = hay(modelText);
        Set<String> found = new LinkedHashSet<>();
        for (String term : INSTRUCTIONS) {
            if (hits(hay, term)) {
                found.add("dose_change:" + term.replace("*", ""));
            }
        }
        for (String sentence : sentences(modelText)) {
            String s = hay(sentence);
            String joined = hay(INNER_HYPHEN.matcher(sentence).replaceAll(""));
            for (String term : IMPERATIVES) {
                if (s.startsWith(" " + term + " ")) {
                    found.add("dose_change:" + term + "+imperative");
                }
            }
            boolean medicine = refersToMedicine(s, medicineNames) || refersToMedicine(joined, medicineNames);
            if (!medicine) {
                continue;
            }
            for (String term : IMPERATIVES_NEAR_MEDICINE) {
                if (s.startsWith(" " + term + " ")) {
                    found.add("dose_change:" + term + "+imperative+medicine");
                }
            }
            boolean aboutAValue = VALUE_WORDS.stream().anyMatch(t -> hits(s, t));
            boolean medicineBesideLab = !aboutAValue
                    || refersToMedicine(withoutLabs(s, labNames), medicineNames)
                    || refersToMedicine(withoutLabs(joined, labNames), medicineNames);
            for (String term : NEAR_MEDICINE) {
                if (hits(s, term) && (medicineBesideLab || !CHANGE_WORDS.contains(term))) {
                    found.add("dose_change:" + term.replace("*", "") + "+medicine");
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** A sentence in {@link #hay} form with every stored lab's name, as a whole word or phrase, taken out. */
    static String withoutLabs(String hay, Set<String> labNames) {
        String out = hay;
        for (String lab : labNames == null ? Set.<String>of() : labNames) {
            String word = " " + lab + " ";
            while (!lab.isBlank() && out.contains(word)) {
                out = out.replace(word, "  ");
            }
        }
        return out;
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
        // a stored name is a stem, so a name spelled on ("Demoprole") or with a case ending glued on still counts
        return medicineNames != null && medicineNames.stream().anyMatch(n -> hits(hay, n + "*"));
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
