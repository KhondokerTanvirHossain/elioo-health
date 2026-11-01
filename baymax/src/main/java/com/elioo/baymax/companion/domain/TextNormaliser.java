package com.elioo.baymax.companion.domain;

import com.elioo.baymax.outbound.domain.PrintedRange;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one normal form the চ্যাট's code checks compare in (DR-50): a question against the red-flag phrases, a model's text
 * against the dose-change and reassurance terms. Both sides of every comparison pass through here, so the two can never
 * disagree on encoding. {@code WaTextRouter.normalise}'s steps, plus digits of any script folded to ASCII:
 * <ol>
 *   <li>NFKC (so a Bangla letter typed as base plus nukta and its precomposed twin are one string);</li>
 *   <li>ZWNJ and ZWJ dropped (keyboards insert them invisibly inside Bangla conjuncts);</li>
 *   <li>lower case;</li>
 *   <li>every run of punctuation or whitespace to one space, trimmed (the danda "।", "?" and "-" included);</li>
 *   <li>a Latin letter repeated three or more times squeezed to two ("plsss" → "plss");</li>
 *   <li>Bangla (and every other script's) digits to ASCII, as {@code DocumentFacts.fold} does.</li>
 * </ol>
 * {@code WaTextRouter.normalise} is left as it is: WhatsApp is out of DR-50's scope.
 */
public final class TextNormaliser {

    private TextNormaliser() {
    }

    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String out = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("‌", "").replace("‍", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{IsPunctuation}\\s]+", " ")
                .replaceAll("([a-z])\\1{2,}", "$1$1")
                .trim();
        return PrintedRange.foldDigits(out);
    }
}
