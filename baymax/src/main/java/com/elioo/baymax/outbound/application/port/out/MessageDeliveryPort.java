package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

/** Hands a released or approved message to the family. v1 adapter logs (ids only); WhatsApp is BMX-10. */
public interface MessageDeliveryPort {

    Mono<Void> deliver(OutboundMessage message);
}
