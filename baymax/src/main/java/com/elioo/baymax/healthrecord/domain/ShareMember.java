package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;
import java.util.UUID;

/** An extra WhatsApp number (a sibling) allowed to view one patient. At most one per patient in v1. */
public record ShareMember(
        UUID id,
        UUID patientId,
        String whatsappNumber,
        Instant addedAt,
        Instant notifiedAt
) {
}
