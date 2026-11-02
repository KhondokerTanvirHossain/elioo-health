package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.TextNormaliser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PO ruling P14, "Only what the papers say", in code: চ্যাট tells what is written about a medicine (its name,
 * strength, how to take it) and never what it is for, what it treats or helps with, or what kind of medicine it is; that
 * question goes to the doctor or the pharmacist. The live re-measure of #170 found Sonnet writing "এটি সাধারণত রক্তচাপ
 * নিয়ন্ত্রণে ব্যবহৃত হয়" about the fictional Demoprol, a claim no paper could have made. Two questions, both answered on
 * {@link TextNormaliser}'s normal form, never by a model:
 * <ul>
 *   <li>{@link #route}: whether the family's question asks what a medicine is for, does or treats, whether it is for X, or
 *       what kind of medicine it is. {@link Route#CODE} when that is all it asks: code answers from the stored lines
 *       ({@link MedicinePurposeReply}) and no model is called. {@link Route#MIXED} when it also asks something else: the
 *       model answers the whole question (code never splits a family's sentence for it), every check runs, and code appends
 *       {@code chat.purpose.ask} as it appends the dose line. {@link Route#NONE} otherwise. A question that names a stored
 *       lab or a paper ("what is creatinine for?", "রিপোর্টটা কিসের?") is not a medicine's.</li>
 *   <li>{@link #states}: whether a model's own words state what a medicine is for, as {@code medicine_purpose:<term>}
 *       codes, an AnswerCheck violation: the draft goes to the one repair, then the fixed reply, never a silent rewrite.
 *       Run after every stored line is replaced by {@link DoseChangeGuard#RX_LINE}, so a line the paper itself prints, its
 *       printed indication included ("ঘুমের জন্য"), is never held against the model; the model retyping it is.</li>
 * </ul>
 * How a sentence is read, in this order:
 * <ol>
 *   <li>{@link #SAFE} phrases and {@link #DEFERRALS} (a question named and sent on: "কীসের জন্য, তা ডাক্তার বলবেন", "the
 *       papers do not say what Demoprol is for") are taken out; what remains is checked;</li>
 *   <li>{@link #STRONG} terms are refused anywhere ("used for", "is for", "treats", a drug class, "জন্য দেওয়া", "সারায়"), as
 *       is a {@link #USAGE} word with a {@link #FOR} word, a {@link #CONDITIONS} word with a {@link #FOR} word, and a
 *       condition before a medicine noun ("ব্যথার ওষুধ", "a blood pressure medicine", {@link #ofMedicine}). The one
 *       exception is a sentence that names a stored lab and no medicine: a lab's explanation is DR-50's prompt-only rule,
 *       never P14's;</li>
 *   <li>{@link #NEAR} terms ("for", "জন্য", "ব্যবহৃত", "helps", "ধরনের", "কমায়") are refused in a sentence about a
 *       medicine: one that refers to a medicine ({@link DoseChangeGuard#refersToMedicine}: a stored line, a medicine word, a
 *       stored name as a stem and across an inner hyphen, #170's fuzzy match), one with a {@link #PRONOUNS} word, and one
 *       that follows a medicine sentence (Bangla drops the subject: "{{rx:1}}। সাধারণত প্রেশার কমানোর জন্য দেওয়া হয়।"). A
 *       pronoun or following sentence that names a stored lab or a {@link #VALUE_WORDS} word is about the value, not the
 *       medicine. {@link #CONDITIONS} words count only beside a medicine or a pronoun, never only by following one.</li>
 * </ol>
 * All are blocklists, as {@link DoseChangeGuard}'s are: a phrasing no corpus row names can still pass, and the model check
 * the PO approved as P15 (a separate PR) reads what they miss. A term ending in {@code *} is a stem; every other term is a
 * whole word or phrase.
 */
public final class MedicinePurposeGuard {

    /** Where a question goes. */
    public enum Route { CODE, MIXED, NONE }

    /** The handler a code-built purpose reply is stored under (V30). */
    public static final String HANDLER = "medicine_purpose";

    /** Phrases that name medicine without being about one: taken out first. */
    static final List<String> SAFE = List.of("চিকিৎসা পরামর্শ", "medical advice");

    /** A question named and sent on, never a claim: taken out of a sentence before it is checked. */
    static final List<String> DEFERRALS = List.of("কীসের জন্য", "কিসের জন্য", "কিশের জন্য", "কী জন্য", "কি জন্য", "কেন দেওয়া হয়েছে",
            "কেন দেওয়া হয়", "কেন দেওয়া", "কেন দেয়া হয়েছে", "কেন দেয়া", "কেন লেখা হয়েছে", "কেন লেখা", "কী কাজ করে", "কি কাজ করে", "কী কাজে",
            "কি কাজে", "কী কাজ", "কি কাজ", "কীসের ওষুধ", "কিসের ওষুধ", "কোন ধরনের", "কী ধরনের", "কি ধরনের", "কীসের", "কিসের",
            "kiser jonno", "kisher jonno", "ki jonno", "ki kaj kore", "ki kaj", "keno deya hoyeche", "keno deya");

    /** English deferrals, on the normal form: "what Demoprol is for", "what it does", "why it was prescribed". */
    static final List<Pattern> DEFERRAL_PATTERNS = List.of(
            Pattern.compile(" what (?:kind|type|sort|class) of(?: medicine| drug| medication)?(?= )"),
            Pattern.compile(" what (?:\\S+ ){0,4}?(?:is|are|was|were|s) (?:used |given |prescribed |meant )?for(?= )"),
            Pattern.compile(" what (?:\\S+ ){0,4}?(?:does|do|did) (?:\\S+ ){0,2}?(?:do|treat|cure|help|work)(?= )"),
            Pattern.compile(" why (?:\\S+ ){0,5}?(?:prescribed|given|written)(?= )"));

    /** What makes a sentence about a medicine without its name. */
    static final List<String> PRONOUNS = List.of("এটি", "এটা", "এটার", "এটির", "এটাকে", "ইহা", "ওটা", "ওটি", "এগুলো", "এগুলি", "সেটা",
            "সেটি", "it", "its", "they", "them", "this is", "this one", "that is", "these are", "eta", "eti", "eita", "oita", "egulo");

    /** What makes a pronoun or a following sentence about a value on a paper, never a medicine. */
    static final List<String> VALUE_WORDS = List.of("range*", "value*", "result*", "level*", "reading*", "সীমা*", "মান*", "ফলাফল*");

    /** Refused anywhere, unless the sentence names a stored lab and no medicine. */
    static final List<String> STRONG = List.of(
            // English
            "is for", "are for", "be for", "was for", "were for", "it s for", "that s for", "used for", "used to", "used in",
            "given for", "given to", "prescribed for", "prescribed to", "meant for", "good for", "helps with", "help with",
            "helps to", "helps in", "works for", "works on", "treat*", "cure*", "remed*",
            // Bangla
            "জন্য দেওয়া*", "জন্য দেয়া*", "জন্য দেন", "জন্য দেয়", "জন্য ব্যবহৃত*", "জন্য ব্যবহার*", "জন্য খাওয়া*", "জন্য খেতে",
            "ব্যবহৃত হয়", "কাজে লাগে", "কাজ করে", "উপকারী", "উপকার করে", "সারায়", "সারাতে", "সারিয়ে*", "নিরাময়*", "উপশম*", "আরোগ্য*",
            // Banglish
            "kaje lage", "kaj kore", "jonno deya", "jonno dey", "jonno den", "jonno dewa", "jonno use", "jonno khay",
            // what kind of medicine it is: a drug class
            "antibiotic*", "painkiller*", "pain killer*", "pain reliever*", "analgesic*", "antacid*", "antihistamin*",
            "antihypertensiv*", "anti hypertensiv*", "antidiabetic*", "anti diabetic*", "antidepressant*", "antipyretic*",
            "anti inflammator*", "nsaid*", "blood thinner*", "anticoagulant*", "diuretic*", "steroid*", "beta blocker*",
            "ace inhibitor*", "statin*", "sedative*", "sleeping pill*", "tranquili*", "antifungal*", "antiviral*", "laxative*",
            "proton pump*", "অ্যান্টিবায়োটিক*", "এন্টিবায়োটিক*", "এন্টিবায়োটিক*", "ব্যথানাশক*", "ব্যাথানাশক*", "জ্বরনাশক*", "স্টেরয়েড*",
            "এন্টাসিড*", "অ্যান্টাসিড*", "ঘুমের বড়ি*", "জীবাণুনাশক*");

    /** Refused in a sentence about a medicine (named, a pronoun, or following one). */
    static final List<String> NEAR = List.of(
            // English
            "for", "used", "use", "uses", "using", "help*", "work", "works", "working", "control*", "manag*", "lower*", "reduc*",
            "bring down", "brings down", "prevent*", "protect*", "reliev*", "relief", "kind of", "type of", "class of", "group of",
            "family of", "sort of", "kind", "type", "belongs to", "known as", "called", "purpose*", "effect*", "indicat*", "heals", "healing",
            "fight*", "against", "acts on", "act on", "target*", "regulat*", "balanc*",
            // Bangla
            "জন্য*", "জন্যে", "ব্যবহৃত*", "ব্যবহার*", "সাহায্য*", "কাজ*", "চিকিৎসা*", "নিয়ন্ত্রণ*", "নিয়ন্ত্রন*", "কমায়*", "কমাতে",
            "কমানো*", "কমানোর", "কমিয়ে*", "প্রতিরোধ*", "রক্ষা*", "উপকার*", "কার্যকর*", "ধরনের", "ধরণের", "রকমের", "জাতীয়*",
            "শ্রেণি*", "শ্রেণী*", "গ্রুপ*", "উদ্দেশ্য*", "ঠিক রাখ*", "স্বাভাবিক রাখ*", "রাখে",
            // Banglish
            "jonno", "jonne", "kaj*", "komay", "komate", "komano*", "niyontron*");

    /** What a medicine is for, as a family names it; refused beside a medicine or a pronoun, and with a {@link #FOR} word. */
    static final List<String> CONDITIONS = List.of(
            // English
            "blood pressure", "pressure", "bp", "hypertension", "fever", "pain*", "diabet*", "sugar", "infection*", "gastric",
            "gastri*", "acid*", "ulcer*", "allerg*", "cholesterol*", "heart", "thyroid*", "sleep*", "insomnia", "anxiety", "cough*",
            "cold", "asthma*", "kidney*", "liver*", "vomit*", "nausea", "diarrh*", "constipation*", "headache*", "inflammation*",
            "arthritis*", "depression*", "anemia*", "anaemia*", "nerve*",
            // Bangla
            "রক্তচাপ*", "প্রেশার*", "প্রেসার*", "জ্বর*", "ব্যথা*", "ব্যাথা*", "ডায়াবেটিস*", "ডায়াবেটিক*", "সুগার*", "ইনফেকশন*", "সংক্রমণ*",
            "গ্যাস্ট্রিক*", "গ্যাস*", "এসিডিটি*", "অ্যাসিডিটি*", "আলসার*", "এলার্জি*", "অ্যালার্জি*", "কোলেস্টেরল*", "হার্ট*", "হৃদ*",
            "থাইরয়েড*", "ঘুম*", "দুশ্চিন্তা*", "কাশি*", "ঠান্ডা*", "সর্দি*", "হাঁপানি*", "শ্বাসকষ্ট*", "কিডনি*", "লিভার*", "বমি*",
            "ডায়রিয়া*", "পাতলা পায়খানা*", "কোষ্ঠকাঠিন্য*", "মাথাব্যথা*", "প্রদাহ*", "বাত*", "রক্তশূন্যতা*", "স্নায়ু*",
            // Banglish
            "jor*", "betha*", "batha*", "bethar", "gas*", "sugar*", "presar*", "pesar*");

    /** A word that uses a medicine, which with a {@link #FOR} word says what it is for. */
    static final List<String> USAGE = List.of("give", "gives", "given", "giving", "gave", "prescrib*", "use", "used", "uses", "using",
            "দেওয়া*", "দেয়া*", "দেন", "দেয়", "দিয়ে থাক*", "ব্যবহৃত*", "ব্যবহার*", "খাওয়ানো*", "deya", "dey", "den", "dewa");

    static final List<String> FOR = List.of("for", "জন্য*", "জন্যে", "jonno", "jonne");

    /** What a medicine is called as a noun, the word {@link #ofMedicine} looks before. */
    static final List<String> MEDICINE_NOUNS = List.of("ওষুধ*", "ঔষধ*", "ঔষুধ*", "oshudh*", "osudh*", "oushod*", "medicine*",
            "medication*", "drug*");

    /** A Bangla word ending in র before a medicine noun that is a possessor, not what the medicine is for. */
    static final Set<String> POSSESSORS = words("আর", "আমার", "আপনার", "তোমার", "তার", "তাঁর", "ওর", "ওনার", "উনার", "এর", "র", "ের",
            "এঁর", "যার", "কার", "তাদের", "আমাদের", "প্রেসক্রিপশনের", "কাগজের", "ডাক্তারের", "রিপোর্টের", "আগের", "পরের", "শেষের",
            "আম্মার", "আম্মুর", "মায়ের", "মার", "আব্বার", "আব্বুর", "বাবার", "নানার", "নানুর", "নানির", "দাদার", "দাদুর", "দাদির",
            "ভাইয়ের", "বোনের", "স্বামীর", "স্ত্রীর", "ছেলের", "মেয়ের", "খালার", "ফুফুর", "চাচার", "মামার", "er", "r", "amar", "apnar",
            "tomar", "tar", "or", "ar", "ammar", "abbar", "mayer", "babar");

    /** An English word before a medicine noun that does not say what kind of medicine it is. */
    static final Set<String> EN_BEFORE_MEDICINE = words("a", "an", "the", "this", "that", "these", "those", "her", "his", "their",
            "its", "your", "my", "our", "any", "other", "no", "same", "only", "one", "each", "every", "which", "what", "whose", "new",
            "old", "latest", "last", "prescribed", "written", "listed", "another", "s", "of", "some", "such", "all", "both", "either",
            "neither", "different", "first", "second", "own", "or", "and", "about", "on", "in", "rxline", "is", "was", "are");

    // --- the question side

    /** A question that asks what a medicine is for, does or treats, or what kind it is. */
    static final List<String> PURPOSE_ASKS = List.of("কীসের", "কিসের", "কিশের", "কী জন্য", "কি জন্য", "কেন দেওয়া*", "কেন দেয়া*",
            "কেন দিয়েছে*", "কেন দিলেন", "কেন লিখেছে*", "কেন খা*", "কী কাজ*", "কি কাজ*", "কাজ কী", "কাজ কি", "কী ধরনের", "কি ধরনের",
            "কোন ধরনের", "কোন রোগের", "কী রোগের", "কি রোগের", "কী উপকার*", "কি উপকার*", "উপকার কী", "উপকার কি",
            "kiser", "kisher", "kise", "ki kaj", "kaj ki", "ki jonno", "keno deya*", "keno dise*", "keno dilo", "kon rog*",
            "ki dhoroner", "kon dhoroner", "what kind of", "what type of", "what sort of", "what class of");

    static final List<Pattern> PURPOSE_ASK_PATTERNS = List.of(
            Pattern.compile(" what (?:is|s|are|was|were) (?:\\S+ ){0,5}?for(?= )"),
            Pattern.compile(" what (?:does|do|did) (?:\\S+ ){1,5}?(?:do|treat|cure|help|work)(?= )"),
            Pattern.compile(" what (?:\\S+ ){0,3}?(?:used|given|prescribed) for(?= )"),
            Pattern.compile(" why (?:\\S+ ){0,6}?(?:prescrib\\S*|given|give|written|taking|take)(?= )"),
            Pattern.compile(" (?:used|given|prescribed) for what(?= )"));

    /** A question about a paper or a lab is not a medicine's. */
    static final List<String> PAPER_WORDS = List.of("রিপোর্ট*", "কাগজ*", "টেস্ট*", "পরীক্ষা*", "প্রেসক্রিপশন*", "মান*", "সীমা*",
            "report*", "paper*", "test*", "prescription*", "value*", "result*", "level*", "range*", "reading*", "kagoj*");

    /** Labs a family types in Bangla, which no stored (printed, English) name matches. */
    static final List<String> LAB_WORDS = List.of("ক্রিয়েটিনিন*", "হিমোগ্লোবিন*", "ইউরিয়া*", "ইউরিক*", "কোলেস্টেরল টেস্ট*",
            "ট্রাইগ্লিসারাইড*", "বিলিরুবিন*", "এসজিপিটি*", "টিএসএইচ*", "প্লাটিলেট*", "প্লেটলেট*", "এইচবিএওয়ানসি*", "hba1c", "creatinine*",
            "hemoglobin*", "haemoglobin*", "urea", "bilirubin*", "sgpt", "tsh", "platelet*", "triglycerid*", "cbc", "esr");

    /** Asking how much, when or where: never part of a purpose question. */
    static final List<String> OTHER_ASKS = List.of("কত*", "কবে", "কখন", "কোথায়", "কেমন", "how", "when", "where", "much", "many",
            "koto", "kobe", "kokhon", "kothay", "kemon");

    static final List<String> COURTESY = List.of("ধন্যবাদ", "আচ্ছা", "জি", "জ্বি", "হ্যাঁ", "হ্যালো", "সালাম", "আসসালামু আলাইকুম", "ভাই",
            "আপা", "প্লিজ", "দয়া করে", "thanks", "thank you", "thank", "you", "please", "plz", "pls", "ok", "okay", "hi", "hello",
            "salam", "dhonnobad", "accha", "ji", "bhai", "apa");

    /** Words that carry no name: what is left of a question once they are gone is a name the papers may not hold. */
    static final Set<String> STOP = words(
            "কি", "কী", "কেন", "কোন", "কোনো", "জন্য", "জন্যে", "এটা", "এটি", "এই", "ওই", "সেই", "ঐ", "টা", "টি", "হয়", "হয়েছে", "দেওয়া",
            "দেয়া", "দেয়", "দিয়েছে", "দিয়েছেন", "দিলেন", "লিখেছেন", "লিখেছে", "লেখা", "করে", "করছে", "কাজ", "রোগের", "ধরনের", "উপকার",
            "আর", "ও", "এবং", "বা", "আমার", "আমি", "আপনি", "তো", "কিন্তু", "আসলে", "একটু", "বলুন", "বলেন", "বলো", "বলবেন", "জানতে",
            "চাই", "এর", "র", "ের", "খায়", "খাচ্ছে", "খাচ্ছেন", "খান", "খাওয়া", "খাওয়ার", "দেন", "দেয়", "তা", "যে", "জানাবেন",
            "what", "is", "s", "are", "was", "were", "the", "a", "an", "this", "that", "these", "it", "its", "for", "used", "use", "do",
            "does", "did", "why", "prescribed", "given", "give", "kind", "type", "sort", "class", "of", "her", "his", "my", "ma",
            "mom", "mother", "father", "dad", "to", "treat", "treats", "help", "helps", "and", "about", "tell", "me", "can", "you",
            "please", "work", "works", "taking", "take", "she", "he", "we", "i", "or", "also", "medicine", "drug",
            "ki", "kiser", "kisher", "kise", "eta", "eti", "ei", "jonno", "keno", "kaj", "kore", "deya", "hoyeche", "dise", "er", "r",
            "ta", "ti", "ar", "ba", "amar", "dilo", "rog", "roger", "dhoroner");

    /** Family words the patient may be called by, plain or possessive. */
    static final Set<String> KIN = words("আম্মা", "আম্মু", "মা", "আব্বা", "আব্বু", "বাবা", "নানা", "নানু", "নানি", "দাদা", "দাদু", "দাদি",
            "ammu", "amma", "abbu", "abba", "baba", "nana", "nani", "dada", "dadi");

    /** The sets above in normal form, which is what a sentence's words are compared in. */
    private static final Set<String> STOP_N = normalised(STOP);
    private static final Set<String> KIN_N = normalised(KIN);
    private static final Set<String> POSSESSORS_N = normalised(POSSESSORS);

    /** "আর", "and", "ও": a question's parts, each read on its own. */
    private static final Pattern CONJUNCTION = Pattern.compile(" (?:আর|এবং|ও|and|ar|also|plus) ");
    /** A question's sentence breaks, and a comma. */
    private static final Pattern CLAUSE = Pattern.compile("[!?？।;\\n,，]+|\\.(?!\\d)");
    private static final Pattern INNER_HYPHEN = Pattern.compile("(?<=[\\p{L}\\p{M}])[-‐‑–](?=[\\p{L}\\p{M}])");

    private MedicinePurposeGuard() {
    }

    // --- the answer side

    /**
     * The purpose claims in a model's own text (every stored line already {@link DoseChangeGuard#RX_LINE}), as
     * {@code medicine_purpose:<term>} codes in a fixed order; empty is a pass. {@code medicineNames} and {@code labNames} are
     * in normal form; {@code patientName} is the asked patient's, whose possessive before a medicine noun is no claim.
     */
    public static List<String> states(String ownWords, Set<String> medicineNames, Set<String> labNames, String patientName) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> possessors = possessors(patientName);
        boolean earlierMedicine = false;
        for (String sentence : DoseChangeGuard.sentences(ownWords)) {
            String raw = DoseChangeGuard.hay(sentence);
            String joined = DoseChangeGuard.hay(INNER_HYPHEN.matcher(sentence).replaceAll(""));
            boolean medicine = DoseChangeGuard.refersToMedicine(raw, medicineNames) || DoseChangeGuard.refersToMedicine(joined, medicineNames);
            String s = withoutDeferrals(raw);
            boolean lab = names(raw, labNames);
            boolean value = lab || VALUE_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(raw, t));
            boolean pronoun = PRONOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(s, t));
            boolean labOnly = lab && !medicine;
            // the stored names taken out, so a medicine called "Statinex" is never the class "statin*"
            String unnamed = without(s, medicineNames);
            if (!labOnly) {
                for (String term : STRONG) {
                    if (DoseChangeGuard.hits(unnamed, term)) {
                        found.add("medicine_purpose:" + term.replace("*", ""));
                    }
                }
                boolean forWord = FOR.stream().anyMatch(t -> DoseChangeGuard.hits(s, t));
                if (forWord && USAGE.stream().anyMatch(t -> DoseChangeGuard.hits(s, t))) {
                    found.add("medicine_purpose:usage+for");
                }
                if (forWord && CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(unnamed, t))) {
                    found.add("medicine_purpose:condition+for");
                }
                found.addAll(ofMedicine(unnamed, possessors));
            }
            boolean aboutMedicine = medicine || (!value && (pronoun || earlierMedicine));
            if (aboutMedicine) {
                for (String term : NEAR) {
                    if (DoseChangeGuard.hits(s, term)) {
                        found.add("medicine_purpose:" + term.replace("*", "") + "+medicine");
                    }
                }
            }
            if (medicine || (pronoun && !value)) {
                for (String term : CONDITIONS) {
                    if (DoseChangeGuard.hits(unnamed, term)) {
                        found.add("medicine_purpose:condition+medicine");
                    }
                }
            }
            earlierMedicine = earlierMedicine || medicine;
        }
        return new ArrayList<>(found);
    }

    /**
     * What kind of medicine, said as the word before a medicine noun: "ব্যথার ওষুধ", "jorer oshudh", "a blood pressure
     * medicine". Fail closed: a Bangla word ending in র refuses unless it is a possessor ({@link #POSSESSORS}, the patient's
     * own possessive); an English word refuses unless it is a determiner or a word of the papers
     * ({@link #EN_BEFORE_MEDICINE}).
     */
    static List<String> ofMedicine(String hay, Set<String> possessors) {
        List<String> out = new ArrayList<>();
        // runs of spaces, which taking a deferral or a name out leaves, are one break: no empty word stands before a noun
        String[] words = hay.strip().split(" +");
        for (int i = 1; i < words.length; i++) {
            String w = " " + words[i] + " ";
            if (MEDICINE_NOUNS.stream().noneMatch(t -> DoseChangeGuard.hits(w, t))) {
                continue;
            }
            String noun = words[i];
            String before = words[i - 1];
            boolean claim;
            if (noun.chars().anyMatch(c -> c >= 0x0980 && c <= 0x09FF)) {
                // ওষুধ: a word ending in র, or a Banglish genitive, that is not a possessor
                claim = (before.endsWith("র") || before.endsWith("er")) && !possessors.contains(before);
            } else if (noun.startsWith("o")) {
                // oshudh, osudh, oushod: a Banglish genitive ("jorer oshudh") that is not a possessor
                claim = (before.endsWith("er") || before.endsWith("r") || before.endsWith("র")) && !possessors.contains(before);
            } else {
                // medicine, medication, drug: any word but a determiner or a word of the papers
                claim = !EN_BEFORE_MEDICINE.contains(before);
            }
            if (claim) {
                out.add("medicine_purpose:of+medicine");
            }
        }
        return out;
    }

    // --- the question side

    /**
     * Where the family's question goes: {@link Route#CODE} when every part of it asks what a medicine is for (or is a
     * courtesy, or a bare name), {@link Route#MIXED} when one part does and another asks something else, {@link Route#NONE}
     * when no part does. {@code labNames} are the stored labs' names in normal form (empty before the context is read: the
     * service routes again once it is).
     */
    public static Route route(String question, Set<String> medicineNames, Set<String> labNames, String patientName) {
        boolean purpose = false;
        boolean other = false;
        for (String clause : clauses(question)) {
            if (asksPurpose(clause, question, medicineNames, labNames)) {
                purpose = true;
            } else if (!compatible(clause, medicineNames, labNames, patientName)) {
                other = true;
            }
        }
        if (!purpose) {
            return Route.NONE;
        }
        return other ? Route.MIXED : Route.CODE;
    }

    /** The question's parts in {@link DoseChangeGuard#hay} form: split at sentence breaks, commas and "আর"/"and". */
    static List<String> clauses(String question) {
        List<String> out = new ArrayList<>();
        for (String part : CLAUSE.split(question == null ? "" : question)) {
            for (String c : CONJUNCTION.split(DoseChangeGuard.hay(part))) {
                if (!c.isBlank()) {
                    out.add(" " + c.strip() + " ");
                }
            }
        }
        return out;
    }

    private static boolean asksPurpose(String clause, String question, Set<String> medicineNames, Set<String> labNames) {
        if (PAPER_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t)) || LAB_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || names(clause, labNames)) {
            return false;
        }
        if (PURPOSE_ASKS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || PURPOSE_ASK_PATTERNS.stream().anyMatch(p -> p.matcher(clause).find())) {
            return true;
        }
        boolean condition = CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean forWord = FOR.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean medicineNoun = MEDICINE_NOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean asked = question.contains("?") || question.contains("？") || clause.startsWith(" is ") || clause.startsWith(" are ")
                || clause.startsWith(" does ") || clause.startsWith(" do ") || DoseChangeGuard.hits(clause, "কি")
                || DoseChangeGuard.hits(clause, "কী") || DoseChangeGuard.hits(clause, "ki");
        return condition && (forWord || medicineNoun) && asked;
    }

    /** A part that asks nothing else: a courtesy, a name, a pronoun, a medicine word, nothing left beyond one name. */
    private static boolean compatible(String clause, Set<String> medicineNames, Set<String> labNames, String patientName) {
        if (OTHER_ASKS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || PAPER_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || LAB_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t)) || names(clause, labNames)) {
            return false;
        }
        return leftover(clause, medicineNames, patientName).size() <= 1;
    }

    /**
     * What is left of a question's words once the purpose phrases, the words that carry no name, the patient's name, the
     * family words, the pronouns, the medicine words, the conditions and the stored medicines' names are gone: a name the
     * papers may not hold ("napa").
     */
    static List<String> leftover(String hay, Set<String> medicineNames, String patientName) {
        String h = hay;
        for (String p : PURPOSE_ASKS) {
            h = h.replace(" " + TextNormaliser.normalise(p.replace("*", "")) + " ", "  ");
        }
        for (String c : COURTESY) {
            h = h.replace(" " + TextNormaliser.normalise(c) + " ", "  ");
        }
        Set<String> patient = new LinkedHashSet<>();
        for (String w : TextNormaliser.normalise(patientName == null ? "" : patientName).split(" ")) {
            patient.add(w);
        }
        patient.addAll(possessors(patientName));
        List<String> out = new ArrayList<>();
        for (String w : h.strip().split(" +")) {
            String word = " " + w + " ";
            if (w.isBlank() || STOP_N.contains(w) || patient.contains(w) || KIN_N.contains(w) || KIN_N.contains(stripPossessive(w))
                    || PRONOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || DoseChangeGuard.MEDICINE_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || (medicineNames != null && medicineNames.stream().anyMatch(n -> DoseChangeGuard.hits(word, n + "*")))
                    || w.chars().allMatch(Character::isDigit)) {
                continue;
            }
            out.add(w);
        }
        return out;
    }

    /** Which stored lines (from 1) the question names, by each line's medicine name as a stem and across a hyphen. */
    static List<Integer> linesNamed(String question, ChatContext ctx) {
        String hay = DoseChangeGuard.hay(question);
        String joined = DoseChangeGuard.hay(INNER_HYPHEN.matcher(question == null ? "" : question).replaceAll(""));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ctx.verbatimLines().size(); i++) {
            for (String n : ChatContext.namesOfLine(ctx.verbatimLines().get(i))) {
                if (DoseChangeGuard.hits(hay, n + "*") || DoseChangeGuard.hits(joined, n + "*")) {
                    out.add(i + 1);
                    break;
                }
            }
        }
        return out;
    }

    /** The stored labs' names in normal form, as the checks read them. */
    public static Set<String> labNames(ChatContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (ChatContext.Fact f : ctx.facts()) {
            String n = TextNormaliser.normalise(f.name());
            if (!n.isBlank()) {
                out.add(n);
            }
        }
        return out;
    }

    /** The asked patient's name, from the context's {@code PATIENT} line; empty when there is none. */
    public static String patientOf(ChatContext ctx) {
        for (String line : ctx.factLines()) {
            if (line.startsWith("PATIENT ")) {
                return line.substring("PATIENT ".length());
            }
        }
        return "";
    }

    private static boolean names(String hay, Set<String> labNames) {
        return labNames != null && labNames.stream().anyMatch(n -> !n.isBlank() && DoseChangeGuard.hits(hay, n));
    }

    /** The sentence with its safe phrases and deferrals taken out. */
    static String withoutDeferrals(String hay) {
        String out = hay;
        for (String p : SAFE) {
            out = out.replace(" " + TextNormaliser.normalise(p) + " ", "  ");
        }
        for (Pattern p : DEFERRAL_PATTERNS) {
            out = p.matcher(out).replaceAll(" ");
        }
        for (String d : DEFERRALS) {
            String n = " " + TextNormaliser.normalise(d) + " ";
            while (out.contains(n)) {
                out = out.replace(n, "  ");
            }
        }
        return out;
    }

    private static String without(String hay, Set<String> names) {
        String out = hay;
        for (String n : names == null ? Set.<String>of() : names) {
            if (!n.isBlank()) {
                out = Pattern.compile(" " + Pattern.quote(n) + "\\S*").matcher(out).replaceAll(" ");
            }
        }
        return out;
    }

    /** The patient's possessive forms, as a sentence in normal form writes them ("ma র" is the words "ma", "র"). */
    private static Set<String> possessors(String patientName) {
        Set<String> out = new LinkedHashSet<>(POSSESSORS_N);
        if (patientName != null && !patientName.isBlank()) {
            for (String w : TextNormaliser.normalise(NameGrammar.possessive(patientName)).split(" ")) {
                out.add(w);
            }
        }
        return out;
    }

    /** A set of words, a repeat allowed (a list maintained by hand can name a word twice). */
    private static Set<String> words(String... words) {
        return Set.copyOf(List.of(words));
    }

    private static Set<String> normalised(Set<String> words) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words) {
            out.add(TextNormaliser.normalise(w));
        }
        return Set.copyOf(out);
    }

    private static String stripPossessive(String w) {
        for (String end : List.of("য়ের", "ের", "র")) {
            String n = TextNormaliser.normalise(end);
            if (w.endsWith(n) && w.length() > n.length()) {
                return w.substring(0, w.length() - n.length());
            }
        }
        return w;
    }
}
