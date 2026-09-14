package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The account behind one WhatsApp number: the family owner, who consents to the terms and pays.
 *
 * @param whatsappNumber E.164; the only place a phone number is stored, never used in keys or logs
 * @param termsAcceptedAt set by the server at creation; immutable
 */
public record FamilyAccount(
        UUID id,
        String whatsappNumber,
        String ownerName,
        Plan plan,
        Instant termsAcceptedAt,
        Instant createdAt
) {
    public enum Plan {
        FREE, FAMILY;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Plan fromDbValue(String v) {
            return valueOf(v.toUpperCase(java.util.Locale.ROOT));
        }
    }

    public boolean isFree() {
        return plan == Plan.FREE;
    }
}
