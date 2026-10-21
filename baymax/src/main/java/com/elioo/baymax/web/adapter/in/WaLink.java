package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.adapter.in.ui.Lang;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * D37: the wa.me deep link to Medioo's own number with the sign-in word typed in, in the viewer's language — the word
 * {@code WaTextRouter} answers with a code. One link for the HTML code page and the sign-in JSON.
 */
public final class WaLink {

    private WaLink() {
    }

    /** The link, or null when no public number is configured. Not HTML-escaped: a page escapes it where it writes it. */
    public static String of(Lang lang, String publicNumber) {
        String number = publicNumber == null ? "" : publicNumber.trim();
        if (number.isEmpty()) {
            return null;
        }
        String word = lang == Lang.BN ? "কোড" : "code";
        return "https://wa.me/" + number + "?text=" + URLEncoder.encode(word, StandardCharsets.UTF_8);
    }
}
