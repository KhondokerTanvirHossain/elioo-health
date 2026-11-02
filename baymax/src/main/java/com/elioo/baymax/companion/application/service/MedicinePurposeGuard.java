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
 *       lab or a paper ("what is creatinine for?", "রিপোর্টটা কিসের?") is not a medicine's, nor is one whose ask refers to
 *       no medicine ("what is the date for the next appointment?"). A part that reports a condition, an allergy or a
 *       reaction, or asks whether it is safe, makes the question MIXED (review of #171, findings 2, 3, 8).</li>
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
 *       condition before a medicine noun ("ব্যথার ওষুধ", "a blood pressure medicine", {@link #ofMedicine}), and a
 *       {@link #CONDITIONS} word with an {@link #EFFECTS} or {@link #GIVEN} word ("Napa সাধারণত জ্বর কমায়", "উচ্চ রক্তচাপে
 *       দেওয়া হয়"), whatever the sentence calls the medicine (review of #171, finding 1). The one exception is a lab's own
 *       sentence: a stored lab or a {@link #LAB_WORDS} word, no medicine, no pronoun, not right after a medicine sentence
 *       (finding 5, 7): a lab's explanation is DR-50's prompt-only rule, never P14's;</li>
 *   <li>{@link #NEAR} terms ("for", "জন্য", "ব্যবহৃত", "helps", "ধরনের", "কমায়") are refused in a sentence about a
 *       medicine: one that refers to a medicine ({@link DoseChangeGuard#refersToMedicine}: a stored line, a medicine word, a
 *       stored name as a stem and across an inner hyphen, #170's fuzzy match; or {@link NameSkeleton}, a stored or typed name
 *       in Bangla script), one with a {@link #PRONOUNS} word, and the one sentence right after a medicine sentence (Bangla
 *       drops the subject: "{{rx:1}}। সাধারণত প্রেশার কমানোর জন্য দেওয়া হয়।"), unless its own subject is a paper
 *       ({@link #PAPER_SUBJECTS}, finding 10). A pronoun or following sentence that names a lab or a {@link #VALUE_WORDS}
 *       word is about the value for these neutral words, but never for an {@link #EFFECTS} verb ("It lowers the blood sugar
 *       level", finding 7). A use and a condition beside a medicine, a pronoun or a bare "this" are refused without a "for"
 *       ("Doctors often prescribe this when the sugar is high").</li>
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

    /**
     * What a medicine does to a condition (review of #171, findings 1 and 7). With a {@link #CONDITIONS} word it is refused in
     * any sentence that is not only a lab's, whatever the sentence calls the medicine ("Napa সাধারণত জ্বর কমায়", "ডেমোপ্রল
     * প্রেশার কমায়", the name typed in a script no stored name is in); beside a pronoun or in the sentence after a medicine
     * sentence it is refused even with a value word ("It lowers the blood sugar level"). A comparison ("lower than") is
     * taken out first: it says where a value stands, never what a medicine does.
     */
    static final List<String> EFFECTS = List.of(
            // English
            "lower*", "reduc*", "control*", "help*", "keep", "keeps", "treat*", "cure*", "reliev*", "prevent*", "manag*", "fight*",
            "regulat*", "stabili*", "bring down", "brings down", "bringing down",
            // Bangla
            "কমায়*", "কমাতে", "কমানো*", "কমিয়ে*", "কমাবে*", "নিয়ন্ত্রণ*", "নিয়ন্ত্রন*", "সারায়*", "সারাতে", "সারিয়ে*", "নামায়*", "নামাতে",
            "নামিয়ে*", "ঠিক রাখ*", "স্বাভাবিক রাখ*", "বাড়ায়", "উপশম*", "প্রতিরোধ*",
            // Banglish
            "komay*", "komate", "komano*", "kombe", "namay*", "namate", "niyontron*", "saray*", "thik rakh*");

    /** "brings the glucose result down": an effect whose words stand apart. */
    static final Pattern EFFECT_APART = Pattern.compile(" bring\\S* (?:\\S+ ){1,4}?down(?= )");

    /** A comparison, which says where a value stands: taken out before {@link #EFFECTS} are looked for. */
    static final Pattern COMPARISON = Pattern.compile(" (?:lower|less|higher|more) than(?= )| lower (?:limit|end|side|range)(?= )");

    /** Giving a medicine, which with a {@link #CONDITIONS} word says what it is for ("উচ্চ রক্তচাপে দেওয়া হয়"). */
    static final List<String> GIVEN = List.of("দেওয়া হয়*", "দেয়া হয়*", "দেয়", "দেন", "দিয়ে থাক*", "given", "prescrib*", "deya hoy",
            "dewa hoy");

    /** A bare demonstrative, which with a {@link #USAGE} word and a condition makes the sentence a medicine's. */
    static final List<String> DEMONSTRATIVES = List.of("this", "these", "that", "এটা", "এটি", "এই");

    /**
     * What makes the sentence after a medicine sentence about the papers, not the medicine: its own subject is a paper
     * ("Bring the papers to the doctor for the next visit"). Review of #171, finding 10: only the neutral {@link #NEAR}
     * words are spared by it; a claim is still refused by every rule that needs no medicine reference.
     */
    static final List<String> PAPER_SUBJECTS = List.of("paper*", "কাগজ*", "report*", "রিপোর্ট*", "document*", "photo*", "ছবি*",
            "visit*", "appointment*", "kagoj*");

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

    /**
     * The English asks. The first three end in "for": a question asks what its subject is for only when nothing but a
     * courtesy or the patient follows that "for" ("what is the date for the next appointment?" asks a date, review of #171,
     * finding 2), as {@link #ENDS_AT_FOR} marks them.
     */
    static final List<Pattern> PURPOSE_ASK_PATTERNS = List.of(
            Pattern.compile(" what (?:is|s|are|was|were) (?:\\S+ ){0,5}?for(?= )"),
            Pattern.compile(" what (?:\\S+ ){0,3}?(?:used|given|prescribed) for(?= )"),
            Pattern.compile(" what (?:condition|conditions|disease|diseases|illness|problem|problems|sickness) (?:\\S+ ){0,4}?for(?= )"),
            Pattern.compile(" what (?:does|do|did) (?:\\S+ ){1,5}?(?:do|treat|cure|help|work)(?= )"),
            Pattern.compile(" why (?:\\S+ ){0,6}?(?:prescrib\\S*|given|give|written|taking|take)(?= )"),
            Pattern.compile(" (?:used|given|prescribed) for what(?= )"),
            Pattern.compile(" (?:is|are|s) for what(?= )"),
            Pattern.compile(" for what(?= )"),
            Pattern.compile(" what for(?= )"));

    /** How many of {@link #PURPOSE_ASK_PATTERNS}, from the first, end in "for". */
    static final int ENDS_AT_FOR = 3;

    /**
     * What a yes/no question asks a medicine is or does (review of #171, finding 3: "Is Demoprol an antibiotic?", "Does Napa
     * reduce fever?"): a drug class or a claim word anywhere, or a {@link #CONDITIONS} word with an {@link #EFFECTS} verb, a
     * {@link #FOR} word or a medicine noun. A condition alone is not a purpose question ("Demoprol খেলে কি মাথাব্যথা হয়?"
     * asks what it may cause), and goes to the model.
     */
    static final List<String> QUESTION_CLAIMS = List.of("used for", "good for", "works for", "helps with", "help with", "treat*",
            "cure*", "উপকারী", "কাজে লাগে", "সারায়*", "নিরাময়*", "উপশম*", "kaje lage",
            "antibiotic*", "painkiller*", "pain killer*", "pain reliever*", "analgesic*", "antacid*", "antihistamin*",
            "antihypertensiv*", "antidiabetic*", "antidepressant*", "antipyretic*", "anti inflammator*", "nsaid*", "blood thinner*",
            "anticoagulant*", "diuretic*", "steroid*", "beta blocker*", "ace inhibitor*", "statin*", "sedative*", "sleeping pill*",
            "antifungal*", "antiviral*", "laxative*", "অ্যান্টিবায়োটিক*", "এন্টিবায়োটিক*", "ব্যথানাশক*", "ব্যাথানাশক*", "জ্বরনাশক*",
            "স্টেরয়েড*", "এন্টাসিড*", "অ্যান্টাসিড*", "ঘুমের বড়ি*");

    /** A question's marks of asking: a clause that opens with one of these is a question. */
    static final List<String> QUESTION_OPENERS = List.of(" is ", " are ", " does ", " do ", " did ", " can ", " could ", " will ",
            " would ", " was ");

    /**
     * Whether a question is safe, or reports an allergy or a reaction: never answered by code's line alone (finding 8), so the
     * model answers the whole question and the report is acknowledged.
     */
    static final List<String> SAFETY = List.of("safe*", "pregnan*", "গর্ভ*", "allerg*", "এলার্জি*", "অ্যালার্জি*", "এলার্জ*",
            "side effect*", "পার্শ্বপ্রতিক্রিয়া*", "নিরাপদ*", "breastfeed*", "বুকের দুধ", "react*", "প্রতিক্রিয়া*");

    /** A clause with one of these as its subject says something of the patient: it is a report, never a courtesy. */
    static final List<String> SUBJECTS = List.of("she", "he", "they", "সে", "উনি", "তিনি", "ওনার", "উনার");

    /**
     * Words a family's question names that are not a medicine: one of these left over is never the medicine typed ("what is
     * the reason for her fever?", "আম্মার পেটে ব্যথা কিসের জন্য?", finding 2).
     */
    static final List<String> NOT_NAMES = List.of("reason*", "cause*", "date*", "day*", "time*", "appointment*", "visit*", "advice",
            "doctor*", "nurse*", "hospital*", "clinic*", "food*", "diet*", "meal*", "water", "problem*", "symptom*", "next", "last",
            "tomorrow", "today", "week*", "month*", "year*", "trip", "check*", "stomach*", "head", "chest", "back", "body",
            "কারণ*", "তারিখ*", "দিন*", "সময়*", "ডাক্তার*", "পরামর্শ*", "খাবার*", "সমস্যা*", "হাসপাতাল*", "কাল", "আজ", "পরশু",
            "সপ্তাহ*", "মাস*", "বছর*", "পানি*", "পেট*", "মাথা*", "বুক*", "শরীর*", "পিঠ*", "কোমর*", "পা", "পায়ে", "হাত*", "চোখ*",
            "যেতে", "যাব*", "আসতে", "হবে", "লাগবে", "কোনটা", "কোনটি", "karon", "tarikh", "din", "kal", "aj", "aaj", "pet*");

    /** Prepositions a question puts around a name ("for fever in pregnancy"), which carry no name. */
    static final Set<String> PREPOSITIONS = words("in", "on", "at", "with", "during", "after", "before", "from", "if", "পর",
            "আগে", "থেকে", "খেলে", "দিলে");

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
    private static final Set<String> PREPOSITIONS_N = normalised(PREPOSITIONS);
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
        // review of #171, finding 10: Bangla drops the subject of the sentence right after a medicine sentence, so that one
        // sentence is the medicine's; the rule does not run on past it (a claim further on is held by the rules below that
        // need no medicine reference: STRONG, a condition with "for", a condition with an effect)
        boolean previousMedicine = false;
        for (String sentence : DoseChangeGuard.sentences(ownWords)) {
            String raw = DoseChangeGuard.hay(sentence);
            String joined = DoseChangeGuard.hay(INNER_HYPHEN.matcher(sentence).replaceAll(""));
            // a stored name, or one the family typed, written in either script or a vowel off (NameSkeleton, finding 1)
            boolean medicine = DoseChangeGuard.refersToMedicine(raw, medicineNames) || DoseChangeGuard.refersToMedicine(joined, medicineNames)
                    || NameSkeleton.anyWord(raw, medicineNames);
            String s = withoutDeferrals(raw);
            // a lab the family or the model writes in Bangla ("ক্রিয়েটিনিন") is a lab as its stored English name is (finding 5)
            boolean lab = names(raw, labNames) || LAB_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(raw, t));
            boolean value = lab || VALUE_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(raw, t));
            boolean pronoun = PRONOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(s, t));
            boolean follows = previousMedicine && !medicine;
            // a lab's own sentence only: no medicine, no pronoun, not right after a medicine (finding 7)
            boolean labOnly = lab && !medicine && !pronoun && !follows;
            // the stored names taken out, so a medicine called "Statinex" is never the class "statin*"
            String unnamed = without(s, medicineNames);
            boolean condition = CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(unnamed, t));
            String effects = COMPARISON.matcher(unnamed).replaceAll(" ");
            List<String> effect = new ArrayList<>(EFFECTS.stream().filter(t -> DoseChangeGuard.hits(effects, t)).toList());
            if (EFFECT_APART.matcher(effects).find()) {
                effect.add("bring down");
            }
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
                if (forWord && condition) {
                    found.add("medicine_purpose:condition+for");
                }
                // finding 1: a condition and what is done to it, whatever the sentence calls the medicine
                if (condition && (!effect.isEmpty() || GIVEN.stream().anyMatch(t -> DoseChangeGuard.hits(effects, t)))) {
                    found.add("medicine_purpose:condition+effect");
                }
                found.addAll(ofMedicine(unnamed, possessors));
            }
            boolean paperSubject = PAPER_SUBJECTS.stream().anyMatch(t -> DoseChangeGuard.hits(s, t));
            boolean aboutMedicine = medicine || (!value && pronoun) || (follows && !value && !paperSubject);
            if (aboutMedicine) {
                for (String term : NEAR) {
                    if (DoseChangeGuard.hits(s, term)) {
                        found.add("medicine_purpose:" + term.replace("*", "") + "+medicine");
                    }
                }
            }
            // finding 7 (b): what a medicine does is refused beside a pronoun or after a medicine sentence, a value word or not
            if (medicine || pronoun || follows) {
                for (String term : effect) {
                    found.add("medicine_purpose:" + term.replace("*", "") + "+effect");
                }
            }
            if (medicine || (pronoun && !value)) {
                if (condition) {
                    found.add("medicine_purpose:condition+medicine");
                }
            }
            // finding 7 (c): "Doctors often prescribe this when the sugar is high", a use and a condition, no "for"
            boolean demonstrative = DEMONSTRATIVES.stream().anyMatch(t -> DoseChangeGuard.hits(s, t));
            if ((medicine || pronoun || demonstrative || follows) && condition && USAGE.stream().anyMatch(t -> DoseChangeGuard.hits(s, t))) {
                found.add("medicine_purpose:usage+condition");
            }
            previousMedicine = medicine;
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
        List<String> clauses = clauses(question);
        // "Demoprol, what for?": a clause that is only a name gives the next clause its subject
        boolean nameNearby = clauses.stream().anyMatch(c -> compatible(c, medicineNames, labNames, patientName)
                && refers(c, medicineNames, patientName));
        boolean purpose = false;
        boolean other = false;
        for (String clause : clauses) {
            if (asksPurpose(clause, question, medicineNames, labNames, patientName, nameNearby)) {
                purpose = true;
                // is it safe, an allergy, a reaction: the model answers the whole question (review of #171, finding 8)
                if (SAFETY.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))) {
                    other = true;
                }
            } else if (!compatible(clause, medicineNames, labNames, patientName)) {
                other = true;
            }
        }
        if (!purpose) {
            return Route.NONE;
        }
        return other ? Route.MIXED : Route.CODE;
    }

    /**
     * The words of the question's purpose parts that name no stored medicine and no known word: the name the family typed
     * ("napa", "ডেমোপ্রল"), which the answer check counts as a medicine (review of #171, finding 1 b). Empty when the
     * question asks no medicine's purpose.
     */
    public static Set<String> askedNames(String question, Set<String> medicineNames, Set<String> labNames, String patientName) {
        Set<String> out = new LinkedHashSet<>();
        List<String> clauses = clauses(question);
        boolean nameNearby = clauses.stream().anyMatch(c -> compatible(c, medicineNames, labNames, patientName)
                && refers(c, medicineNames, patientName));
        boolean any = clauses.stream().anyMatch(c -> asksPurpose(c, question, medicineNames, labNames, patientName, nameNearby));
        if (!any) {
            return out;
        }
        for (String clause : clauses) {
            boolean asks = asksPurpose(clause, question, medicineNames, labNames, patientName, nameNearby);
            boolean name = compatible(clause, medicineNames, labNames, patientName);
            if (asks || name) {
                for (String w : leftover(clause, medicineNames, patientName)) {
                    if (!notAName(w) && w.codePointCount(0, w.length()) >= 3) {
                        out.add(w);
                    }
                }
            }
        }
        return out;
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

    /**
     * Whether a clause asks a medicine's purpose. It must refer to a medicine (review of #171, finding 2): a stored name (in
     * either script, NameSkeleton), a medicine noun, a pronoun, exactly one word left that is not a known non-name (the name
     * typed), or a name in a clause of its own beside it. Then either an ask ("কীসের", "what is … for" with nothing after
     * "for", "what for", "for what"), unless a condition comes before the ask and only a typed word names the medicine
     * ("পেটে ব্যথা কিসের জন্য?" asks about the pain); or a yes/no question (finding 3) holding a claim word or a drug class,
     * or a condition with what is done to it, a "for" or a medicine noun.
     */
    private static boolean asksPurpose(String clause, String question, Set<String> medicineNames, Set<String> labNames,
                                       String patientName, boolean nameNearby) {
        if (PAPER_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t)) || LAB_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || names(clause, labNames)) {
            return false;
        }
        boolean explicit = explicitlyRefers(clause, medicineNames);
        List<String> left = leftover(clause, medicineNames, patientName);
        boolean typed = left.size() == 1 && !notAName(left.get(0));
        boolean ref = explicit || typed || (nameNearby && left.isEmpty());
        if (!ref) {
            return false;
        }
        int ask = askAt(clause);
        if (ask >= 0) {
            return explicit || !conditionBefore(clause, ask);
        }
        boolean asked = question.contains("?") || question.contains("？") || QUESTION_OPENERS.stream().anyMatch(clause::startsWith)
                || DoseChangeGuard.hits(clause, "কি") || DoseChangeGuard.hits(clause, "কী") || DoseChangeGuard.hits(clause, "ki");
        if (!asked) {
            return false;
        }
        if (QUESTION_CLAIMS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))) {
            return true;
        }
        boolean condition = CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean forWord = FOR.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean medicineNoun = MEDICINE_NOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        boolean effect = EFFECTS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
        return condition && (forWord || medicineNoun || effect);
    }

    /** Where in the clause a purpose ask begins, or -1; an ask ending in "for" counts only when nothing but a name follows. */
    private static int askAt(String clause) {
        int at = -1;
        for (String t : PURPOSE_ASKS) {
            int i = indexOf(clause, t);
            if (i >= 0 && (at < 0 || i < at)) {
                at = i;
            }
        }
        for (int p = 0; p < PURPOSE_ASK_PATTERNS.size(); p++) {
            java.util.regex.Matcher m = PURPOSE_ASK_PATTERNS.get(p).matcher(clause);
            while (m.find()) {
                if (p >= ENDS_AT_FOR || nothingFollows(clause.substring(m.end()))) {
                    if (at < 0 || m.start() < at) {
                        at = m.start();
                    }
                    break;
                }
            }
        }
        return at;
    }

    /** What follows an ask's "for": only courtesies, the patient, words that carry no name. */
    private static boolean nothingFollows(String rest) {
        String h = " " + rest.strip() + " ";
        if (h.isBlank()) {
            return true;
        }
        for (String w : h.strip().split(" +")) {
            String word = " " + w + " ";
            boolean empty = STOP_N.contains(w) || KIN_N.contains(w) || COURTESY.stream().anyMatch(c -> DoseChangeGuard.hits(word, c));
            if (!empty) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionBefore(String clause, int ask) {
        for (String t : CONDITIONS) {
            int i = indexOf(clause, t);
            if (i >= 0 && i < ask) {
                return true;
            }
        }
        return false;
    }

    /** Where a term ({@link DoseChangeGuard#hits}' form) starts in a clause, or -1. */
    private static int indexOf(String hay, String term) {
        boolean stem = term.endsWith("*");
        String n = TextNormaliser.normalise(stem ? term.substring(0, term.length() - 1) : term);
        if (n.isEmpty()) {
            return -1;
        }
        return hay.indexOf(stem ? " " + n : " " + n + " ");
    }

    /**
     * A stored name (as a stem, across a hyphen, or by skeleton in either script), a medicine noun, or a pronoun. A clause is
     * in normal form, where "Demo-prol" is the two words "demo prol": two words that join into a stored name are that name.
     */
    private static boolean explicitlyRefers(String clause, Set<String> medicineNames) {
        String[] w = clause.strip().split(" +");
        StringBuilder pairs = new StringBuilder(" ");
        for (int i = 0; i + 1 < w.length; i++) {
            pairs.append(w[i]).append(w[i + 1]).append(' ');
        }
        String joined = pairs.toString();
        return (medicineNames != null && medicineNames.stream().anyMatch(n -> DoseChangeGuard.hits(clause, n + "*")
                || DoseChangeGuard.hits(joined, n + "*"))) || NameSkeleton.anyWord(clause, medicineNames)
                || MEDICINE_NOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || PRONOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t));
    }

    /** {@link #explicitlyRefers}, or exactly one word left that is not a known non-name: the medicine the family typed. */
    private static boolean refers(String clause, Set<String> medicineNames, String patientName) {
        if (explicitlyRefers(clause, medicineNames)) {
            return true;
        }
        List<String> left = leftover(clause, medicineNames, patientName);
        return left.size() == 1 && !notAName(left.get(0));
    }

    static boolean notAName(String word) {
        String w = " " + word + " ";
        return NOT_NAMES.stream().anyMatch(t -> DoseChangeGuard.hits(w, t));
    }

    /**
     * A part that asks nothing else: a courtesy, a name, a pronoun, a medicine word, nothing left beyond one name. A part
     * that names a condition, has a person as its subject or asks whether it is safe reports something of the patient
     * (review of #171, finding 8: "she is allergic", "বমি হচ্ছে"): it is another question, never a courtesy.
     */
    private static boolean compatible(String clause, Set<String> medicineNames, Set<String> labNames, String patientName) {
        if (OTHER_ASKS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || PAPER_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || LAB_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t)) || names(clause, labNames)
                || CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || SUBJECTS.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))
                || SAFETY.stream().anyMatch(t -> DoseChangeGuard.hits(clause, t))) {
            return false;
        }
        return leftover(clause, medicineNames, patientName).size() <= 1;
    }

    /**
     * What is left of a question's words once the purpose phrases, the words that carry no name, the patient's name, the
     * family words, the pronouns, the medicine words, the conditions, what is done to them, the drug classes, the safety
     * words, the prepositions and the stored medicines' names (in either script) are gone: a name the papers may not hold
     * ("napa").
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
            if (w.isBlank() || STOP_N.contains(w) || PREPOSITIONS_N.contains(w) || patient.contains(w) || KIN_N.contains(w)
                    || KIN_N.contains(stripPossessive(w))
                    || PRONOUNS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || DoseChangeGuard.MEDICINE_WORDS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || CONDITIONS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || EFFECTS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || QUESTION_CLAIMS.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || SAFETY.stream().anyMatch(t -> DoseChangeGuard.hits(word, t))
                    || (medicineNames != null && medicineNames.stream().anyMatch(n -> DoseChangeGuard.hits(word, n + "*")))
                    || NameSkeleton.matches(w, medicineNames)
                    || w.chars().allMatch(Character::isDigit)) {
                continue;
            }
            out.add(w);
        }
        return out;
    }

    /**
     * Which stored lines (from 1) the question names, by each line's medicine name as a stem, across a hyphen, or by
     * skeleton: typed in Bangla script or a vowel off ("ডেমোপ্রল", "Demopral", review of #171, finding 6).
     */
    static List<Integer> linesNamed(String question, ChatContext ctx) {
        String hay = DoseChangeGuard.hay(question);
        String joined = DoseChangeGuard.hay(INNER_HYPHEN.matcher(question == null ? "" : question).replaceAll(""));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ctx.verbatimLines().size(); i++) {
            Set<String> names = ChatContext.namesOfLine(ctx.verbatimLines().get(i));
            for (String n : names) {
                if (DoseChangeGuard.hits(hay, n + "*") || DoseChangeGuard.hits(joined, n + "*") || NameSkeleton.anyWord(hay, Set.of(n))) {
                    out.add(i + 1);
                    break;
                }
            }
        }
        return out;
    }

    /** Whether the question names a medicine of the latest prescription whose line is never shown (DR-16, finding 6). */
    static boolean namesUnshown(String question, ChatContext ctx) {
        String hay = DoseChangeGuard.hay(question);
        String joined = DoseChangeGuard.hay(INNER_HYPHEN.matcher(question == null ? "" : question).replaceAll(""));
        return ctx.unshownNames().stream().anyMatch(n -> DoseChangeGuard.hits(hay, n + "*") || DoseChangeGuard.hits(joined, n + "*"))
                || NameSkeleton.anyWord(hay, ctx.unshownNames());
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
