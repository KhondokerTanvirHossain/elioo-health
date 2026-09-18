package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Approve delivers; reject records the reason and never delivers. Only PENDING rows can be decided. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewGateService implements ReviewGateUseCase {

    private final OutboundMessagePort messages;
    private final MessageDeliveryPort delivery;
    private final Clock clock;

    @Override
    public Flux<OutboundMessage> pending() {
        return messages.pending();
    }

    @Override
    public Mono<OutboundMessage> approve(UUID messageId, String reviewer) {
        Instant now = clock.instant();
        return pendingOrError(messageId)
                .flatMap(m -> messages.decide(messageId, OutboundMessage.GateStatus.APPROVED, reviewer, null, now, now))
                .flatMap(m -> delivery.deliver(m).thenReturn(m))
                .doOnNext(m -> log.info("[baymax] message approved id={} by={} urgency={}", m.id(), reviewer, m.urgency()));
    }

    @Override
    public Mono<OutboundMessage> reject(UUID messageId, String reviewer, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(BaymaxException.badRequest("reason_required", "a reject needs a reason"));
        }
        Instant now = clock.instant();
        return pendingOrError(messageId)
                .flatMap(m -> messages.decide(messageId, OutboundMessage.GateStatus.REJECTED, reviewer, reason, now, null))
                .doOnNext(m -> log.info("[baymax] message rejected id={} by={} urgency={} reason={}", m.id(), reviewer, m.urgency(), reason));
    }

    private Mono<OutboundMessage> pendingOrError(UUID messageId) {
        return messages.find(messageId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("message_not_found", "no message with id " + messageId)))
                .flatMap(m -> m.gateStatus() == OutboundMessage.GateStatus.PENDING ? Mono.just(m)
                        : Mono.error(BaymaxException.conflict("not_pending", "message is " + m.gateStatus().dbValue())));
    }
}
