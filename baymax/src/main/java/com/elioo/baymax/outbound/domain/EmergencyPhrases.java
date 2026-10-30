package com.elioo.baymax.outbound.domain;

import java.util.List;
import java.util.Locale;

/**
 * The document emergency words ({@code baymax.outbound.emergency-phrases}) matched whole-token: the rule
 * {@code UrgencyService} has always used on a paper's text, in one place so the চ্যাট's pre-check (DR-50) reuses it rather
 * than a copy. Every run of characters that are not letters, marks or digits is a word break; a phrase matches only
 * between two breaks, so "urgent" is found in "is this urgent?" and not in "urgently-needed" unless that word is listed.
 */
public final class EmergencyPhrases {

    private EmergencyPhrases() {
    }

    public static boolean contains(String text, List<String> phrases) {
        if (text == null || phrases == null) {
            return false;
        }
        String hay = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ") + " ";
        for (String phrase : phrases) {
            if (hay.contains(" " + phrase.toLowerCase(Locale.ROOT).trim() + " ")) {
                return true;
            }
        }
        return false;
    }
}
