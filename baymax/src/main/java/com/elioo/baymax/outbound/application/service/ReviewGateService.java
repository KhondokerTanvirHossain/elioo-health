package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
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

    /**
     * The approval is persisted first and is never undone by what delivery does next; the send's outcome is
     * recorded separately (V13). Before this, {@code decide} set {@code sent_at} and {@code deliver} was called
     * without looking at the result — harmless while delivery meant logging, but over WhatsApp a failed send
     * would have left a row claiming APPROVED with {@code sent_at} populated and nothing delivered.
     *
     * <p>The approve call therefore answers 200 with the delivery outcome in the body rather than failing: the
     * approval genuinely happened, the send did not, and both facts belong to the reviewer.
     */
    @Override
    public Mono<OutboundMessage> approve(UUID messageId, String reviewer) {
        Instant now = clock.instant();
        return pendingOrError(messageId)
                // decidedAt is set here; sentAt stays NULL until a provider actually accepts the message
                .flatMap(m -> messages.decide(messageId, OutboundMessage.GateStatus.APPROVED, reviewer, null, now, null))
                .doOnNext(m -> log.info("[baymax] message approved id={} by={} urgency={}", m.id(), reviewer, m.urgency()))
                .flatMap(m -> delivery.deliver(m)
                        .onErrorResume(e -> {
                            log.error("[baymax] message delivery threw id={} : {}", m.id(), e.toString());
                            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED));
                        })
                        .flatMap(outcome -> outcome.status() == null
                                // the log adapter delivered nothing; keep v1 behaviour and stamp sent_at
                                ? messages.markSent(m.id(), now).then(messages.find(m.id()))
                                : messages.recordDelivery(m.id(), outcome, now)
                                        .doOnNext(saved -> log.info("[baymax] message delivery recorded id={} status={} error={}",
                                                saved.id(), outcome.status(), outcome.error()))));
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
