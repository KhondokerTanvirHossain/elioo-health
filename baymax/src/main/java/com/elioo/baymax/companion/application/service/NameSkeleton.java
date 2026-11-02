package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.TextNormaliser;

import java.util.Map;
import java.util.Set;

/**
 * A medicine name's consonant skeleton, the same for its Latin spelling and its Bangla one, so a stored name a family
 * types in Bangla script ("ডেমোপ্রল" for the stored "demoprol", "নাপা" for "napa") or with a vowel wrong ("Demopral") is
 * still the stored name (review of #171, finding 6). Vowels are dropped, sounds Bangla writes with one letter where Latin
 * writes two ("ph", "sh", "kh") are one consonant, and each consonant falls into the class a Bangla keyboard would type it
 * as (ট and ত are both "t"; চ is "ch"; a Latin "c" is "k" unless an e, i or y follows). A skeleton of fewer than three
 * consonants matches nothing: two letters are too common to say a name was meant.
 *
 * <p>Code's own deterministic reading, never a model's; it only ever makes a word count as a stored name, which on the
 * answer side refuses more and on the question side shows a stored line verbatim, never a claim.
 */
final class NameSkeleton {

    /** The fewest consonants a skeleton must hold to name a medicine. */
    static final int MIN = 3;

    private static final Map<Character, Character> BANGLA = Map.ofEntries(
            Map.entry('ক', 'k'), Map.entry('খ', 'k'), Map.entry('গ', 'g'), Map.entry('ঘ', 'g'), Map.entry('ঙ', 'n'),
            Map.entry('চ', 'c'), Map.entry('ছ', 'c'), Map.entry('জ', 'j'), Map.entry('ঝ', 'j'), Map.entry('ঞ', 'n'),
            Map.entry('ট', 't'), Map.entry('ঠ', 't'), Map.entry('ড', 'd'), Map.entry('ঢ', 'd'), Map.entry('ণ', 'n'),
            Map.entry('ত', 't'), Map.entry('থ', 't'), Map.entry('দ', 'd'), Map.entry('ধ', 'd'), Map.entry('ন', 'n'),
            Map.entry('প', 'p'), Map.entry('ফ', 'f'), Map.entry('ব', 'b'), Map.entry('ভ', 'b'), Map.entry('ম', 'm'),
            Map.entry('য', 'j'), Map.entry('র', 'r'), Map.entry('ল', 'l'), Map.entry('শ', 's'), Map.entry('ষ', 's'),
            Map.entry('স', 's'), Map.entry('হ', 'h'), Map.entry('ৎ', 't'), Map.entry('ং', 'n'));

    /** Bangla letters that a Latin spelling writes as a vowel or not at all: য় (ya), and য or ব after a hasant (a phala). */
    private static final Set<Character> PHALA = Set.of('য', 'ব');
    private static final char HASANT = '্';
    private static final char NUKTA = '়';

    private NameSkeleton() {
    }

    /** The skeleton of one word, in either script; empty when it holds no consonant. */
    static String of(String word) {
        String w = TextNormaliser.normalise(word);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            char next = i + 1 < w.length() ? w.charAt(i + 1) : ' ';
            Character k;
            if (c >= 0x0980 && c <= 0x09FF) {
                if (next == NUKTA) {
                    // য় is a vowel glide; ড় and ঢ় are r (NFKC leaves them as base plus nukta)
                    k = c == 'য' ? null : (c == 'ড' || c == 'ঢ') ? Character.valueOf('r') : BANGLA.get(c);
                    i++;
                } else if (i > 0 && w.charAt(i - 1) == HASANT && PHALA.contains(c)) {
                    k = null;
                } else {
                    k = BANGLA.get(c);
                }
            } else if (c >= 'a' && c <= 'z') {
                k = latin(c, next);
                if (k != null && next == 'h' && "pskgbdjtc".indexOf(c) >= 0) {
                    // ph, sh, kh, gh, bh, dh, jh, th, ch: one Bangla letter
                    i++;
                }
            } else {
                k = null;
            }
            if (k != null && (out.isEmpty() || out.charAt(out.length() - 1) != k)) {
                out.append(k.charValue());
            }
        }
        return out.toString();
    }

    private static Character latin(char c, char next) {
        return switch (c) {
            case 'a', 'e', 'i', 'o', 'u', 'w', 'y' -> null;
            case 'c' -> next == 'h' ? Character.valueOf('c') : (next == 'e' || next == 'i' || next == 'y') ? Character.valueOf('s')
                    : Character.valueOf('k');
            case 'p' -> next == 'h' ? Character.valueOf('f') : Character.valueOf('p');
            case 'q' -> 'k';
            case 'v' -> 'b';
            case 'z' -> 'j';
            case 'x' -> 'k';
            default -> c;
        };
    }

    /** Whether a word is one of the names, by skeleton; a skeleton too short to name anything matches nothing. */
    static boolean matches(String word, Set<String> names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        String s = of(word);
        if (s.length() < MIN) {
            return false;
        }
        for (String n : names) {
            if (s.equals(of(n))) {
                return true;
            }
        }
        return false;
    }

    /** Whether any word of a text in normal form is one of the names, by skeleton. */
    static boolean anyWord(String hay, Set<String> names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        for (String w : hay.strip().split(" +")) {
            if (matches(w, names)) {
                return true;
            }
        }
        return false;
    }
}
