package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test fake of {@link DocumentUrgencyPort} with the adapter's merge semantics (max of the levels, sorted union of the
 * reasons, the higher verdict per observation). The database behaviour itself, the trigger included, is DocumentUrgencyNeverLowersTest's on Postgres.
 */
public final class InMemoryDocumentUrgency implements DocumentUrgencyPort {

    final Map<UUID, StoredUrgency> rows = new ConcurrentHashMap<>();
    /** Every assessment handed to raise(), in order, before the merge. */
    final List<UrgencyAssessment> raised = new CopyOnWriteArrayList<>();

    @Override
    public Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at) {
        raised.add(assessed);
        StoredUrgency merged = rows.compute(documentId, (id, old) -> {
            TreeSet<String> reasons = new TreeSet<>(assessed.reasons());
            Urgency level = assessed.level();
            // F2: per observation, the higher verdict is kept (V19's trigger and the adapter's guarded UPDATE)
            Map<UUID, ValueVerdict> verdicts = new LinkedHashMap<>();
            if (old != null) {
                reasons.addAll(old.reasons());
                level = Urgency.max(old.level(), level);
                old.values().forEach(v -> verdicts.put(v.observationId(), v));
            }
            for (ValueVerdict v : assessed.values()) {
                if (v.observationId() == null) {
                    throw new IllegalStateException("verdict without observation id");
                }
                verdicts.merge(v.observationId(), v, ValueVerdict::max);
            }
            return new StoredUrgency(id, level, reasons.stream().toList(), at, List.copyOf(verdicts.values()));
        });
        return Mono.just(merged);
    }

    /** A row as if an earlier assessment had stored it; not counted in {@link #raised}. */
    public void preset(UUID documentId, Urgency level, List<String> reasons) {
        rows.put(documentId, new StoredUrgency(documentId, level, reasons, Instant.EPOCH));
    }

    @Override
    public Mono<StoredUrgency> find(UUID documentId) {
        return Mono.justOrEmpty(rows.get(documentId));
    }

    /** The sweep's queue, as a test sets it; the predicate itself is the adapter's, on Postgres. */
    final List<UUID> queue = new CopyOnWriteArrayList<>();
    /** What happened, in order: "attempt:<id>" here, and whatever the test appends around it (e.g. "assess:<id>"). */
    final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public Flux<UUID> needingAssessment(LocalDate today, int limit) {
        return Flux.fromIterable(queue).take(limit);
    }

    @Override
    public Mono<Void> markAttempt(UUID documentId, Instant at) {
        return Mono.fromRunnable(() -> events.add("attempt:" + documentId));
    }
}
