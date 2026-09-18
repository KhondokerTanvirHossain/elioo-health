package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.WebSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface SessionStorePort {

    Mono<WebSession> create(String tokenHash, UUID familyId, Instant now, Instant expiresAt);

    /** The session behind a token hash, only while unexpired and unrevoked. */
    Mono<WebSession> findLive(String tokenHash, Instant now);

    /** Sliding expiry: note the use and push the deadline forward. */
    Mono<Void> touch(UUID sessionId, Instant now, Instant expiresAt);

    Mono<Void> revoke(String tokenHash, Instant at);
}
