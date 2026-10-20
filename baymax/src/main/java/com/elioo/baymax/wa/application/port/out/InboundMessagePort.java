package com.elioo.baymax.wa.application.port.out;

import com.elioo.baymax.wa.domain.InboundMessage;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * The record of inbound WhatsApp messages (F9a). "Answered once" rests on {@link #claim}: the database's unique key, not
 * a check in code, decides which of two deliveries of one message goes on to reply.
 */
public interface InboundMessagePort {

    /**
     * Records the message unless its key is already there.
     *
     * @return the stored row when THIS call inserted it; empty when the key was already claimed (a re-delivery), which
     *         is not an error. Errors only when nothing could be recorded.
     */
    Mono<InboundMessage> claim(InboundMessage message);

    /** Stamps the outcome once; a later stamp for the same key is a no-op, never an overwrite. */
    Mono<Void> markHandled(String providerKey, String outcome);

    /** A photo or file that became a document: the patient it was filed under and the document. */
    Mono<Void> linkDocument(String providerKey, UUID patientId, UUID documentId);

    /** Messages received before {@code before} that were never stamped handled: what a restart cut off. */
    Mono<Long> countUnhandledBefore(Instant before);
}
