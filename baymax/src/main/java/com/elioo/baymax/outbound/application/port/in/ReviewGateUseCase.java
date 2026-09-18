package com.elioo.baymax.outbound.application.port.in;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The review gate (DR-13). Nothing PENDING is delivered without {@link #approve}. */
public interface ReviewGateUseCase {

    Flux<OutboundMessage> pending();

    Mono<OutboundMessage> approve(UUID messageId, String reviewer);

    Mono<OutboundMessage> reject(UUID messageId, String reviewer, String reason);
}
