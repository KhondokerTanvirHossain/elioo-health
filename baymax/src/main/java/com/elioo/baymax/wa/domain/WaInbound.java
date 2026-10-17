package com.elioo.baymax.wa.domain;

import java.time.Instant;
import java.util.Locale;

/**
 * One inbound WhatsApp message, mapped from Meta's JSON at the adapter boundary. {@code text} is the typed body, a
 * button's title, or a reaction's emoji. It is patient text and is never logged; neither is {@code from} (a phone
 * number) nor {@code providerMessageId} (a wamid is base64 of the phone number).
 *
 * @param timestamp Meta's own send time (epoch seconds in the callback), or null when the callback carried none
 */
public record WaInbound(String providerMessageId, String from, Type type, String text, String mediaId, String mimeType,
                        Instant timestamp) {

    /** Callers and tests with no provider timestamp. */
    public WaInbound(String providerMessageId, String from, Type type, String text, String mediaId, String mimeType) {
        this(providerMessageId, from, type, text, mediaId, mimeType, null);
    }

    public enum Type {
        TEXT, IMAGE, DOCUMENT, AUDIO, VIDEO, STICKER, REACTION, INTERACTIVE, BUTTON, LOCATION, CONTACTS, SYSTEM, OTHER;

        /** Anything Meta adds later, plus "unsupported", "unknown" and "order", is OTHER, which gets the fallback reply. */
        public static Type of(String meta) {
            return switch (meta == null ? "" : meta.toLowerCase(Locale.ROOT)) {
                case "text" -> TEXT;
                case "image" -> IMAGE;
                case "document" -> DOCUMENT;
                case "audio" -> AUDIO;
                case "video" -> VIDEO;
                case "sticker" -> STICKER;
                case "reaction" -> REACTION;
                case "interactive" -> INTERACTIVE;
                case "button" -> BUTTON;
                case "location" -> LOCATION;
                case "contacts" -> CONTACTS;
                case "system" -> SYSTEM;
                default -> OTHER;
            };
        }
    }

    /**
     * Exactly two inbound events carry nothing the family sent, so they get no reply: Meta's own system notices
     * (e.g. a changed number) and an un-reaction (a reaction with its emoji removed). Everything else is answered once.
     */
    public boolean expectsReply() {
        return switch (type) {
            case SYSTEM -> false;
            case REACTION -> text != null && !text.isBlank();
            default -> true;
        };
    }
}
