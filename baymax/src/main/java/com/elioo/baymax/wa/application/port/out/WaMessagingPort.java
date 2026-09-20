package com.elioo.baymax.wa.application.port.out;

import reactor.core.publisher.Mono;

/**
 * What the intake service needs from WhatsApp, expressed without naming it (BMX-10). The Cloud API adapter
 * implements this; nothing above {@code adapter.out} imports the Graph client, which is the hexagonal rule
 * {@code HexagonalArchitectureTest} enforces.
 */
public interface WaMessagingPort {

    /** Fetches an inbound media object's bytes by its provider id. */
    Mono<byte[]> downloadMedia(String mediaId);

    /**
     * Sends a free-form session message, valid only inside the 24-hour window the family's own message opened.
     *
     * @return the provider message id, for correlating the delivery status callback
     */
    Mono<String> sendText(String toNumber, String body);
}
