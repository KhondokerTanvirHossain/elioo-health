package com.elioo.baymax.web.domain;

import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.outbound.domain.OutboundMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * One entry of a patient's চ্যাট thread (plan W5-api): a paper the family sent, or a message the provider accepted for
 * the viewer's own account. Newest first by {@link #at}; {@link #order} breaks a tie on the instant (a paper before the
 * message about it), and the id breaks the rest, so the three together identify an entry and the cursor neither skips
 * nor repeats one.
 */
public sealed interface ThreadItem {

    /** The paper's id, or the message's: unique within its kind. */
    UUID id();

    /** A paper's upload instant ({@code document.created_at}); a message's provider acceptance ({@code sent_at}). */
    Instant at();

    /** The tie-break on {@link #at}, as the read model orders it: {@code paper} sorts above {@code message}. */
    String order();

    /** A paper, by its id: its card is the paper JSON's own body, read separately. */
    record Paper(UUID documentId, Instant at) implements ThreadItem {
        @Override
        public UUID id() {
            return documentId;
        }

        @Override
        public String order() {
            return "paper";
        }
    }

    /**
     * A delivered message: its stored kind and body, as sent. {@code documentId} is null for a message about no paper (a
     * nudge).
     */
    record Message(UUID id, Instant at, OutboundMessage.Kind kind, UUID documentId, String body) implements ThreadItem {
        @Override
        public String order() {
            return "message";
        }
    }

    /**
     * A question the viewer's own account typed in the চ্যাট and exactly what Medioo showed (plan CHAT-api, V29), at the
     * instant it was asked; listed only when the reader asks for asks ({@code ?asks=1}). {@code call999} is decided for the
     * viewer at read time ({@code ViewerLocale}): only an emergency, only on a Bangladeshi number.
     */
    record Ask(UUID id, Instant at, String question, String answer, AnswerKind kind, boolean call999) implements ThreadItem {
        @Override
        public String order() {
            return "ask";
        }

        public Ask forViewer(ViewerLocale viewer) {
            return new Ask(id, at, question, answer, kind, kind == AnswerKind.EMERGENCY && viewer.bangladesh());
        }
    }

    /**
     * Where a thread page starts: strictly after (at, order, id) in the thread's newest-first order. Sent to the client
     * as an opaque {@code t1.} token, so a link cannot be edited into an instant.
     */
    record Cursor(Instant at, String order, UUID id) {

        /** Before every real entry: the first page. A far-future bound, since Instant.MAX does not fit a timestamptz. */
        public static final Cursor FIRST = new Cursor(Instant.parse("9999-12-31T00:00:00Z"), "zzzz",
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        private static final String PREFIX = "t1.";

        public static Cursor after(ThreadItem last) {
            return new Cursor(last.at(), last.order(), last.id());
        }

        public String encode() {
            String clear = at + "|" + order + "|" + id;
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(clear.getBytes(StandardCharsets.UTF_8));
        }

        /** Null or blank: the first page. A {@code t1.} token naming an instant, an order and an id: that cursor. Else 400. */
        public static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return FIRST;
            }
            if (raw.startsWith(PREFIX)) {
                try {
                    String clear = new String(Base64.getUrlDecoder().decode(raw.substring(PREFIX.length())), StandardCharsets.UTF_8);
                    String[] parts = clear.split("\\|", -1);
                    if (parts.length == 3 && ("paper".equals(parts[1]) || "message".equals(parts[1]) || "ask".equals(parts[1]))) {
                        return new Cursor(Instant.parse(parts[0]), parts[1], UUID.fromString(parts[2]));
                    }
                } catch (RuntimeException undecodable) {
                    // fall through to the 400
                }
            }
            throw BaymaxException.badRequest("invalid_cursor", "cursor must be the next_cursor of a previous page");
        }
    }
}
