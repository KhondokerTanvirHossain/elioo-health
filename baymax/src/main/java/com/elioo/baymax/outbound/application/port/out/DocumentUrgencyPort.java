package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * F1: a document's stored urgency (V18). The database rejects any lowering (trigger {@code document_urgency_never_lowers});
 * this port additionally merges, so a correct writer never trips it.
 */
public interface DocumentUrgencyPort {

    /**
     * One statement: level = max(stored, assessed), reasons = stored ∪ assessed, assessed_at = {@code at}. Never stamps
     * updated_at. Returns the row as stored afterwards; empty when there is no such document. Only StoredUrgencyService
     * calls this (A11, ArchUnit).
     */
    Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at);

    /** What is stored; empty when the document was never assessed. */
    Mono<StoredUrgency> find(UUID documentId);
}
