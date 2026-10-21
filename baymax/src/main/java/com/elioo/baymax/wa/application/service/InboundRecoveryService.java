package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.wa.application.port.in.InboundRecoveryUseCase;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;

/** {@link InboundRecoveryUseCase}: the cutoff is this instance's start ({@link WaInstance}), never a guess. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class InboundRecoveryService implements InboundRecoveryUseCase {

    private final InboundMessagePort inbound;
    private final WaInstance instance;
    private final Clock clock;

    @Override
    public Mono<Long> closeCutOff() {
        return Mono.defer(() -> inbound.closeUnhandledBefore(instance.startedAt()));
    }

    @Override
    public Mono<Long> purgeUnknownSenders() {
        return Mono.defer(() -> inbound.purgeUnknownSendersBefore(clock.instant().minus(UNKNOWN_SENDER_RETENTION)));
    }
}
