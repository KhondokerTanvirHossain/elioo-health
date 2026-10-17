package com.elioo.baymax.outbound;

import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.MessageCount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Rows in memory, for wiring tests that count what reached the delivery channel. It CANNOT fail the way Postgres
 * fails — V14's CHECKs are not here — so every constraint path these tests touch is also exercised for real in
 * {@code DetailCacheAcceptanceTest}. Do not use this fake to test a constraint.
 */
public final class InMemoryOutboundMessages implements OutboundMessagePort {

    private final Map<UUID, OutboundMessage> rows = new LinkedHashMap<>();
    private final Set<UUID> claimed = new HashSet<>();

    public List<OutboundMessage> rows() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public Mono<OutboundMessage> save(OutboundMessage m) {
        UUID id = UUID.randomUUID();
        OutboundMessage s = new OutboundMessage(id, m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), m.sentAt(), m.createdAt());
        rows.put(id, s);
        return Mono.just(s);
    }

    @Override
    public Mono<OutboundMessage> find(UUID id) {
        return Mono.justOrEmpty(rows.get(id));
    }

    @Override
    public Mono<OutboundMessage> decide(UUID id, OutboundMessage.GateStatus status, String reviewer, String reason, Instant at) {
        OutboundMessage m = rows.get(id);
        OutboundMessage d = new OutboundMessage(id, m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), status, reviewer, reason, at, m.sentAt(), m.createdAt());
        rows.put(id, d);
        return Mono.just(d);
    }

    @Override
    public Mono<Void> markSent(UUID id, Instant at) {
        return Mono.fromRunnable(() -> rows.computeIfPresent(id, (k, m) -> withSentAt(m, at)));
    }

    @Override
    public Mono<OutboundMessage> recordDelivery(UUID id, DeliveryOutcome outcome, Instant at) {
        rows.computeIfPresent(id, (k, m) -> withSentAt(m, outcome.wasSent() ? at : null));
        if (!outcome.wasSent()) {
            claimed.remove(id);   // a failed delivery may be claimed again
        }
        return find(id);
    }

    @Override
    public Flux<OutboundMessage> pending() {
        return Flux.fromIterable(rows()).filter(m -> m.gateStatus() == OutboundMessage.GateStatus.PENDING);
    }

    @Override
    public Mono<OutboundMessage> latestDeliverable(UUID documentId) {
        return Flux.fromIterable(rows()).filter(m -> documentId.equals(m.documentId()) && m.isDeliverable()).last()
                .onErrorResume(NoSuchElementException.class, e -> Mono.empty());
    }

    @Override
    public Flux<MessageCount> counts(Instant from, Instant to) {
        return Flux.empty();
    }

    @Override
    public Mono<Long> consecutiveRetakes(UUID familyId) {
        return Mono.just(0L);
    }

    /** F4. Deliberately no @Override: on the pre-fix port this is an extra method, so the red replay compiles. */
    public Mono<OutboundMessage> latestOfKind(UUID documentId, OutboundMessage.Kind kind) {
        OutboundMessage last = null;
        for (OutboundMessage m : rows.values()) {                 // insertion order is creation order
            if (documentId.equals(m.documentId()) && m.kind() == kind) {
                last = m;
            }
        }
        return Mono.justOrEmpty(last);
    }

    /**
     * F4, no @Override for the same reason. A Set here CANNOT fail like Postgres does; the real claim is one UPDATE and
     * is tested for concurrency in DetailCacheAcceptanceTest. This fake only lets wiring tests count deliveries.
     */
    public Mono<OutboundMessage> claimDelivery(UUID id) {
        OutboundMessage m = rows.get(id);
        if (m == null || m.sentAt() != null || !m.isDeliverable() || !claimed.add(id)) {
            return Mono.empty();
        }
        return Mono.just(m);
    }

    private static OutboundMessage withSentAt(OutboundMessage m, Instant at) {
        return new OutboundMessage(m.id(), m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), at, m.createdAt());
    }
}
