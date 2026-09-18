package com.elioo.baymax.outbound.application.port.in;

import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

/** Compose, check, gate and release one nudge on the BMX-6 path (BMX-8). */
public interface ComposeNudgeUseCase {

    Mono<OutboundMessage> compose(NudgeCandidate candidate, String optOutLink);
}
