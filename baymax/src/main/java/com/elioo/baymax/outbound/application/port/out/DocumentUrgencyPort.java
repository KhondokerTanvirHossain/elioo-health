package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * F1: a document's stored urgency (V18). The database rejects any lowering (trigger {@code document_urgency_never_lowers});
 * this port additionally merges, so a correct writer never trips it.
 */
public interface DocumentUrgencyPort {

    /**
     * One statement: level = max(stored, assessed), reasons = stored ∪ assessed, assessed_at = {@code at}. Never stamps
     * updated_at. F2: in the same transaction, each of {@code assessed.values()} raises its observation's verdict (by
     * observation id; never lowered), and a verdict naming no observation fails the whole call, the level included.
     * Returns the row as stored afterwards, with its stored verdicts; empty when there is no such document. Only
     * StoredUrgencyService calls this (A11, ArchUnit).
     */
    Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at);

    /** What is stored, with the stored verdicts; empty when the document was never assessed. */
    Mono<StoredUrgency> find(UUID documentId);

    /**
     * The sweep's queue: DONE documents never assessed (V18's backfill left them NULL), and DONE documents with a
     * follow-up due BEFORE {@code today} (the patient's day) whose stored reasons do not yet carry
     * {@code follow_up_past_due} — the same predicate as UrgencyService's rule, so one sweep empties it — and (F2) DONE
     * documents with an observation that has no verdict yet (V19's backfill, every re-crop). Documents
     * never attempted come first, then by age: an attempted document goes behind every one not yet attempted.
     */
    Flux<UUID> needingAssessment(LocalDate today, int limit);

    /** Stamps {@code urgency_attempted_at}; never the level, never updated_at. */
    Mono<Void> markAttempt(UUID documentId, Instant at);
}
