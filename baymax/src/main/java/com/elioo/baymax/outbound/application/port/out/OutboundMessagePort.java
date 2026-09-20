package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.MessageCount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface OutboundMessagePort {

    Mono<OutboundMessage> save(OutboundMessage message);

    Mono<OutboundMessage> find(UUID messageId);

    /**
     * Records the reviewer's decision. It does NOT touch sent_at: that field means "the provider accepted it"
     * and only {@link #recordDelivery} or {@link #markSent} may write it (V14 enforces this at the database).
     */
    Mono<OutboundMessage> decide(UUID messageId, OutboundMessage.GateStatus status, String reviewer, String reason,
                                 Instant decidedAt);

    Mono<Void> markSent(UUID messageId, Instant sentAt);

    /**
     * Records what the delivery channel actually did. {@code sentAt} is written ONLY when the provider
     * accepted the message — a failure leaves it NULL and stores the reason, so a reviewer can read
     * {@code sent_at} as "the family received it" (BMX-10, V13).
     */
    Mono<OutboundMessage> recordDelivery(UUID messageId, com.elioo.baymax.outbound.domain.DeliveryOutcome outcome,
                                         Instant sentAt);

    Flux<OutboundMessage> pending();

    /** The newest deliverable message for a document, for the timeline. */
    Mono<OutboundMessage> latestDeliverable(UUID documentId);

    Flux<MessageCount> counts(Instant from, Instant to);

    /** Retake messages this family has received since its last message of any other kind (0 when none). */
    Mono<Long> consecutiveRetakes(UUID familyId);
}
