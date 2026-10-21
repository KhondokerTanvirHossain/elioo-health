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
     * Records the message unless its key is already there. A key already there is reclaimed, once, when an EARLIER
     * instance claimed it and never answered it ({@code handled_at} NULL or outcome {@code CUT_OFF}, received before
     * {@code reclaimBefore}, this instance's start): that claim's dispatch died with its instance, and without the
     * reclaim the re-delivery Meta sends because it never got a 200 would be ignored as a duplicate.
     *
     * @return the stored row when THIS call inserted or reclaimed it; empty when the key was already claimed (a
     *         re-delivery), which is not an error. Errors only when nothing could be recorded.
     */
    Mono<InboundMessage> claim(InboundMessage message, Instant reclaimBefore);

    /** Stamps the outcome once; a later stamp for the same key is a no-op, never an overwrite. */
    Mono<Void> markHandled(String providerKey, String outcome);

    /**
     * A photo or file that became a document: the patient it was filed under and the document, and that patient's family
     * if the claim did not know it (a family registered between the claim and the photo's handling).
     */
    Mono<Void> linkDocument(String providerKey, UUID patientId, UUID documentId);

    /**
     * Closes as {@code CUT_OFF} (handled_at stamped) every message received before {@code before} and never handled, and
     * returns how many it closed: what the latest restart cut off, each row counted once.
     */
    Mono<Long> closeUnhandledBefore(Instant before);

    /** Deletes the rows with no family received before {@code before}; returns how many. */
    Mono<Long> purgeUnknownSendersBefore(Instant before);
}
