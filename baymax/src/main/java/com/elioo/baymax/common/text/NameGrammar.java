package com.elioo.baymax.common.text;

/**
 * Spec §2 F8: the possessive of the name a family entered, built in code so no bundle writes "{patient}-এর". A vowel or
 * vowel sign takes র (আম্মার, আব্বুর); a consonant takes ের (রহিমের); khanda ta becomes ত before it (শরতের); a trailing
 * hasanta is dropped first (জনের); a Latin name keeps a hyphen (Rina-র, John-এর). Anything else — ং ঃ ঁ, a digit,
 * punctuation — takes "-এর", which is never wrong, only plainer. Self mode ("আপনার") arrives with the relation field;
 * Phase 3's companion reuses this helper for {@code {patient_poss}}.
 */
public final class NameGrammar {

    private static final String VOWEL_SIGNS = "ািীুূৃেৈোৌ";   // া ি ী ু ূ ৃ ে ৈ ো ৌ
    private static final String VOWELS = "অআইঈউঊঋএঐওঔ"; // অ আ ই ঈ উ ঊ ঋ এ ঐ ও ঔ
    private static final char HASANTA = '্';
    private static final char KHANDA_TA = 'ৎ';
    private static final char NUKTA = '়';

    private NameGrammar() {
    }

    /** "আম্মা" → "আম্মার", "রহিম" → "রহিমের", "Rina" → "Rina-র"; null or blank → "". Surrounding spaces are dropped. */
    public static String possessive(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        char last = n.charAt(n.length() - 1);
        if (VOWEL_SIGNS.indexOf(last) >= 0 || VOWELS.indexOf(last) >= 0) {
            return n + "র";                                           // র
        }
        if (last == KHANDA_TA) {
            return n.substring(0, n.length() - 1) + "তের";   // ত + ের
        }
        if (last == HASANTA) {
            return n.substring(0, n.length() - 1) + "ের";         // ের
        }
        if (isBanglaConsonant(last) || last == NUKTA) {
            return n + "ের";                                     // ের
        }
        if ((last >= 'a' && last <= 'z') || (last >= 'A' && last <= 'Z')) {
            return n + ("aeiouyAEIOUY".indexOf(last) >= 0 ? "-র" : "-এর");
        }
        return n + "-এর";                                        // -এর
    }

    /**
     * The possessive in a page's or an answer's language: {@link #possessive} for Bangla ({@code "bn"}, and anything not
     * {@code "en"}), and for English the name with "'s", or "'" after a final s ("Ma's", "Chris'"); null or blank → "". The
     * one rule the web pages (UiCopy.poss) and a চ্যাট answer's example (ChatPrompt) both write a name by.
     */
    public static String possessiveIn(String lang, String name) {
        if (!"en".equals(lang)) {
            return possessive(name);
        }
        String n = name == null ? "" : name.strip();
        return n.isEmpty() ? "" : n + (n.endsWith("s") ? "'" : "'s");
    }

    /**
     * The objective of the name (spec §2 F8: "কে" always attaches): "আম্মা" → "আম্মাকে", "রহিম" → "রহিমকে"; a trailing
     * hasanta is dropped first, as in {@link #possessive} ("জন্" → "জনকে"); a name that does not end in a Bangla letter
     * keeps a hyphen ("Rina-কে"); null or blank → "".
     */
    public static String objective(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        if (n.length() > 1 && n.charAt(n.length() - 1) == HASANTA) {
            n = n.substring(0, n.length() - 1);
        }
        char last = n.charAt(n.length() - 1);
        boolean banglaLetter = last >= '\u0980' && last <= '\u09FF' && !(last >= '\u09E6' && last <= '\u09EF');   // not a Bangla digit
        return n + (banglaLetter ? "" : "-") + "\u0995\u09C7";                                                   // কে
    }

    /** ক–হ, and the three precomposed letters with a nukta (ড় ঢ় য়). */
    private static boolean isBanglaConsonant(char c) {
        return (c >= 'ক' && c <= 'হ') || c == 'ড়' || c == 'ঢ়' || c == 'য়';
    }
}
