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
     * @return the provider message id, for correlating the delivery status callback. Never blank: a provider answer
     *         without an id is an error signal, because "no id" is "not accepted" for every caller
     */
    Mono<String> sendText(String toNumber, String body);

    /**
     * Marks an inbound message read (the family sees blue ticks). With {@code typing}, also shows the typing indicator,
     * which WhatsApp clears at our next reply or after 25 seconds. Only for a message we will answer. A courtesy, never
     * part of the answer: completes empty on any failure and never errors.
     *
     * @param providerMessageId the inbound message's wamid
     */
    default Mono<Void> markRead(String providerMessageId, boolean typing) {
        return Mono.empty();
    }
}
