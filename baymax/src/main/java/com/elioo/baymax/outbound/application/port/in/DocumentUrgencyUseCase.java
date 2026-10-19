package com.elioo.baymax.outbound.application.port.in;

import com.elioo.baymax.outbound.domain.StoredUrgency;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** F1: a document's stored, code-computed urgency. Every surface that shows urgency reads it here; nothing else assesses. */
public interface DocumentUrgencyUseCase {

    /** Assess a DONE document from its persisted facts and store max(old, new). Empty for any other status. */
    Mono<StoredUrgency> assessAndStore(UUID documentId);

    /** What is stored; empty when never assessed. Every surface that only lists reads this. */
    Mono<StoredUrgency> current(UUID documentId);

    /**
     * The document page's read: what is stored, or — for a DONE document still unassessed (a pre-V18 row the backfill
     * left NULL) — one assessment through the writer now. A second read finds it stored and assesses nothing.
     */
    Mono<StoredUrgency> currentOrAssess(UUID documentId);
}
