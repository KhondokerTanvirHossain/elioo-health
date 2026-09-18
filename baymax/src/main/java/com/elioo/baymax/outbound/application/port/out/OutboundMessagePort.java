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

    Mono<OutboundMessage> decide(UUID messageId, OutboundMessage.GateStatus status, String reviewer, String reason,
                                 Instant decidedAt, Instant sentAt);

    Mono<Void> markSent(UUID messageId, Instant sentAt);

    Flux<OutboundMessage> pending();

    /** The newest deliverable message for a document, for the timeline. */
    Mono<OutboundMessage> latestDeliverable(UUID documentId);

    Flux<MessageCount> counts(Instant from, Instant to);
}
