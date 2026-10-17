package com.elioo.baymax.wa.application.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whole-message rules for inbound WhatsApp text (spec §7.1 step 2). Zero model calls. Logs never see the text.
 *
 * <p>A rule matches the entire normalised message or nothing, so a longer message that happens to start with a rule
 * word ("বিস্তারিত — আম্মার খুব জ্বর") falls through to the fallback, which carries the red-flag line. D4 holds: no
 * rule here answers a medicine-purpose question; such text is FALLBACK.
 */
public final class WaTextRouter {

    public enum Route { GREETING_HELLO, GREETING_SALAM, GREETING_NAMASKAR, GREETING_ADAB, DETAIL, THANKS, FALLBACK }

    private static final Set<String> SALAM = words("সালাম", "আসসালামু আলাইকুম", "আস্সালামু আলাইকুম", "আসসালামুয়ালাইকুম",
            "সালামালাইকুম", "salam", "slm", "assalamu alaikum", "assalamualaikum", "asalamualaikum", "assalamu alaykum",
            "as salamu alaikum", "salamalaikum", "salamualaikum", "slamalikum", "slamalaikum");
    private static final Set<String> NAMASKAR = words("নমস্কার", "namaskar", "nomoskar", "nomoshkar");
    /** Spec §7.4: আদাব is common in Hindu families; mirrored like নমস্কার, never answered with the fallback. */
    private static final Set<String> ADAB = words("আদাব", "adab", "adaab");
    private static final Set<String> HELLO = words("hi", "hii", "hello", "helo", "hey", "hlw", "hlo", "হাই", "হ্যালো",
            "হেলো", "kemon acho", "kemon achen", "kemon aso", "kemon asen", "kmn acho", "কেমন আছো", "কেমন আছেন", "কেমন আছ",
            "good morning", "good evening", "শুভ সকাল");
    private static final Set<String> DETAIL = words("বিস্তারিত", "বিস্তারিত বলুন", "বিস্তারিত বলো", "বিস্তারিত বলেন",
            "বিস্তারিত জানতে চাই", "আরো বলুন", "আরও বলুন", "আরো বলো", "আরও বলো", "আরো বলেন", "আরও বলেন", "আরো জানতে চাই",
            "আরও জানতে চাই", "বুঝিয়ে বলুন", "বুঝিয়ে বলো", "বুঝিয়ে বলেন", "ব্যাখ্যা", "ব্যাখ্যা করুন",
            "detail", "details", "more", "more detail", "more details", "tell me more", "explain", "explain please",
            "please explain", "bistarito", "bistarito bolun", "bistarito bolo", "bistarito bolen", "bistaritto",
            "aro bolun", "aro bolo", "aro bolen", "bujhiye bolun", "bujhiye bolo", "bujhiye bolen");
    private static final Set<String> THANKS = words("ধন্যবাদ", "অনেক ধন্যবাদ", "থ্যাংক ইউ", "থ্যাঙ্ক ইউ", "থ্যাংকস",
            "ঠিক আছে", "ঠিক আছে ধন্যবাদ", "আচ্ছা", "ওকে", "ok", "okk", "okay", "oky", "k", "thanks", "thank you",
            "thanks a lot", "thx", "tnx", "ty", "ok thanks", "ok thank you", "got it", "dhonnobad", "donnobad",
            "thik ache", "thik ase", "accha", "acha");
    /** Affirmative only: 😢 or 😡 is not "thanks" and must reach the fallback with its red-flag line. */
    private static final Set<Integer> AFFIRMATIVE = Set.of(0x1F44D, 0x1F64F, 0x2764, 0x1F44C, 0x2705, 0x1F60A,
            0x1F642, 0x263A, 0x1F970, 0x1F60D, 0x1F490, 0x1F91D, 0x1F44F);

    private WaTextRouter() {
    }

    public static Route route(String text) {
        if (affirmativeEmojiOnly(text)) {
            return Route.THANKS;
        }
        String n = normalise(text);
        if (n.isEmpty()) {
            return Route.FALLBACK;
        }
        if (DETAIL.contains(n)) {
            return Route.DETAIL;
        }
        if (SALAM.contains(n)) {
            return Route.GREETING_SALAM;
        }
        if (NAMASKAR.contains(n)) {
            return Route.GREETING_NAMASKAR;
        }
        if (ADAB.contains(n)) {
            return Route.GREETING_ADAB;
        }
        if (HELLO.contains(n)) {
            return Route.GREETING_HELLO;
        }
        return THANKS.contains(n) ? Route.THANKS : Route.FALLBACK;
    }

    /**
     * NFKC (U+09DF য় is a composition exclusion, so both of its encodings end as য + nukta), drop ZWNJ/ZWJ, lower-case
     * Latin, punctuation to spaces (the danda is \p{IsPunctuation}), collapse whitespace, and squeeze a Latin letter
     * repeated three or more times to two ("hiii" → "hii").
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("‌", "").replace("‍", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{IsPunctuation}\\s]+", " ")
                .replaceAll("([a-z])\\1{2,}", "$1$1")
                .trim();
    }

    static boolean affirmativeEmojiOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean any = false;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isWhitespace(cp) || cp == 0xFE0F || cp == 0x200D || (cp >= 0x1F3FB && cp <= 0x1F3FF)) {
                continue;   // variation selector, joiner, skin tone
            }
            if (!AFFIRMATIVE.contains(cp)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Table entries go through the same normalisation as input, so the two can never disagree on encoding. */
    private static Set<String> words(String... entries) {
        return Arrays.stream(entries).map(WaTextRouter::normalise).collect(Collectors.toUnmodifiableSet());
    }
}
