package com.elioo.baymax.web.domain;

import java.time.Instant;
import java.util.UUID;

/** One issued code. {@code familyId} is resolved at issue time and null when no account has the number.
 * The code itself is never held in clear past delivery: {@code codeHash} is what is stored. */
public record OtpCode(UUID id, String phoneHash, String codeHash, UUID familyId, Instant expiresAt, int attempts,
                      Instant consumedAt, Instant burnedAt, Instant createdAt) {

    public boolean isLive(Instant now) {
        return consumedAt == null && burnedAt == null && expiresAt.isAfter(now);
    }
}
