package com.elioo.baymax.web.application.port.out;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * When an account holder's WhatsApp number last wrote to Medioo (V17). WhatsApp allows a free-form message, at no
 * cost, only inside the 24-hour window the family's own message opens; the web sign-in sends a code automatically
 * only inside it (DR-39, amended 2026-10-18). Keyed by the phone HMAC, never the number.
 */
public interface InboundWindowPort {

    /** Records an inbound message at {@code at}. The stored time only moves forward. */
    Mono<Void> recordInbound(String phoneHash, UUID familyId, Instant at);

    /** The last inbound message from this number; empty when it has never written. */
    Mono<Instant> lastInbound(String phoneHash);
}
