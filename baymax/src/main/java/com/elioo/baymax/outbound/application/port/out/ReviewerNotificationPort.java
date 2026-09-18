package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

/** Tells the reviewer (DR-13: Tanvir, via WhatsApp) that a message waits. v1 adapter logs; WhatsApp is BMX-10. */
public interface ReviewerNotificationPort {

    Mono<Void> notifyPending(OutboundMessage message);
}
