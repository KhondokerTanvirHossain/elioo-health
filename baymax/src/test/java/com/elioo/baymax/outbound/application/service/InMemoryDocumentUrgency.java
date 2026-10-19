package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test fake of {@link DocumentUrgencyPort} with the adapter's merge semantics (max of the levels, sorted union of the
 * reasons). The database behaviour itself, the trigger included, is DocumentUrgencyNeverLowersTest's on Postgres.
 */
final class InMemoryDocumentUrgency implements DocumentUrgencyPort {

    final Map<UUID, StoredUrgency> rows = new ConcurrentHashMap<>();
    /** Every assessment handed to raise(), in order, before the merge. */
    final List<UrgencyAssessment> raised = new CopyOnWriteArrayList<>();

    @Override
    public Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at) {
        raised.add(assessed);
        StoredUrgency merged = rows.compute(documentId, (id, old) -> {
            TreeSet<String> reasons = new TreeSet<>(assessed.reasons());
            Urgency level = assessed.level();
            if (old != null) {
                reasons.addAll(old.reasons());
                level = Urgency.max(old.level(), level);
            }
            return new StoredUrgency(id, level, reasons.stream().toList(), at);
        });
        return Mono.just(merged);
    }

    @Override
    public Mono<StoredUrgency> find(UUID documentId) {
        return Mono.justOrEmpty(rows.get(documentId));
    }
}
