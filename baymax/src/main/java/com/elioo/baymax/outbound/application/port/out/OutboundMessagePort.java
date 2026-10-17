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

    /**
     * The log adapter's path, which delivers nothing: stamps sent_at with its v1 meaning ("handed over") and clears
     * any delivery claim, so a claimed row it stamps still satisfies V14.
     */
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

    /**
     * The newest message of this kind for a document, whatever its gate state — the DETAIL cache (F4). Ties on
     * created_at prefer a deliverable row, then the larger id, so the answer is the same on every read.
     */
    Mono<OutboundMessage> latestOfKind(UUID documentId, OutboundMessage.Kind kind);

    /**
     * F4: atomically take the right to hand this row to the channel. Emits the row when THIS call took it; empty
     * when it was sent, is being sent, or is not deliverable. A failed delivery may be claimed again.
     */
    Mono<OutboundMessage> claimDelivery(UUID id);

    Flux<MessageCount> counts(Instant from, Instant to);

    /** Retake messages this family has received since its last message of any other kind (0 when none). */
    Mono<Long> consecutiveRetakes(UUID familyId);
}
