package com.elioo.baymax.companion.application.port.out;

/**
 * The fixed lines a চ্যাট answer may carry, unescaped (a JSON body), in the turn's language ({@code "bn"} or {@code "en"}):
 * the UI bundles' keys, and the WhatsApp lines the web words exactly as WhatsApp does ({@code urgency.*},
 * {@code safety.red_flag_line}). A missing key is loud.
 */
public interface ChatCopyPort {

    String line(String lang, String key, Object... args);
}
