package com.elioo.baymax.outbound.application.port.out;

import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

/**
 * Hands a released or approved message to the family.
 *
 * <p>Returns what happened rather than {@code Mono<Void>}: over WhatsApp a send genuinely fails — expired
 * token, a closed 24-hour window, a provider 4xx — and the caller must be able to tell that from success
 * before it writes {@code sent_at}.
 */
public interface MessageDeliveryPort {

    Mono<DeliveryOutcome> deliver(OutboundMessage message);
}
