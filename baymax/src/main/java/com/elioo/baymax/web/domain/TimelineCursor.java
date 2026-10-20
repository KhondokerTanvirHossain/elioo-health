package com.elioo.baymax.web.domain;

import com.elioo.baymax.common.error.BaymaxException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a timeline page starts: strictly after the row (timeline date, upload instant, id) in the timeline's own order,
 * newest first (spec §4.15, V20's {@code timeline_date}). The three parts together identify a row, so rows sharing a
 * date and an instant are neither skipped nor repeated at a page boundary — the defect a {@code created_at}-only cursor
 * had. Sent to the client as an opaque {@code v1.} token, so a link cannot be edited into a date.
 */
public record TimelineCursor(LocalDate date, Instant createdAt, UUID id) {

    /** Before every real row: the first page. Instant.MAX does not fit a timestamptz, so a far-future bound. */
    public static final TimelineCursor FIRST = new TimelineCursor(LocalDate.parse("9999-12-31"),
            Instant.parse("9999-12-31T00:00:00Z"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

    private static final String PREFIX = "v1.";

    public String encode() {
        String clear = date + "|" + createdAt + "|" + id;
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(clear.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Null or blank: the first page. A bare instant (a "আরও দেখুন" link from before C2): the first page, not an error.
     * A {@code v1.} token that decodes to exactly a date, an instant and an id: that cursor. Anything else: 400.
     */
    public static TimelineCursor parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIRST;
        }
        try {
            Instant.parse(raw);
            return FIRST;
        } catch (RuntimeException notAnInstant) {
            // fall through: the current form
        }
        if (raw.startsWith(PREFIX)) {
            try {
                String clear = new String(Base64.getUrlDecoder().decode(raw.substring(PREFIX.length())), StandardCharsets.UTF_8);
                String[] parts = clear.split("\\|", -1);
                if (parts.length == 3) {
                    return new TimelineCursor(LocalDate.parse(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
                }
            } catch (RuntimeException undecodable) {
                // fall through to the 400
            }
        }
        throw BaymaxException.badRequest("invalid_cursor", "cursor must be the next_cursor of a previous page");
    }
}
