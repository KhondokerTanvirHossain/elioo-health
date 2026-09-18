package com.elioo.baymax.web.domain;

import java.time.Instant;
import java.util.UUID;

/** A logged-in browser. Identity is the family account; the cookie token is stored hashed, never here. */
public record WebSession(UUID id, UUID familyId, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
}
