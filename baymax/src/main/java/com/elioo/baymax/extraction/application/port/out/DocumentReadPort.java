package com.elioo.baymax.extraction.application.port.out;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read-side questions about documents that the write port ({@link DocumentRecordPort}) does not answer (B5, B6).
 * Every method reads; none writes.
 */
public interface DocumentReadPort {

    /**
     * 1, 2, 3… for a NEEDS_RETAKE document: this patient's retakes in a row up to and including it, counted from their
     * last finished paper (DONE or FAILED) sent before it. 0 for any other status, and for a document that does not exist.
     * Per patient, where WhatsApp's {@code OutboundMessagePort.consecutiveRetakes} is per family (B5 PR body).
     */
    Mono<Integer> retakeOrdinal(UUID documentId);
}
