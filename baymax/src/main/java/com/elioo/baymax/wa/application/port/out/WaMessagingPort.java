package com.elioo.baymax.wa.application.port.out;

import reactor.core.publisher.Mono;

/**
 * What the intake service needs from WhatsApp, expressed without naming it (BMX-10). The Cloud API adapter
 * implements this; nothing above {@code adapter.out} imports the Graph client, which is the hexagonal rule
 * {@code HexagonalArchitectureTest} enforces.
 */
public interface WaMessagingPort {

    /**
     * Fetches an inbound media object's bytes by its provider id. Fails with {@link MediaUnavailableException} only:
     * the provider's own error names the signed download URL (DR-23), and every caller logs what it is given.
     */
    Mono<byte[]> downloadMedia(String mediaId);

    /** downloadMedia's only error. It carries the HTTP status and nothing else: no URL, no cause (DR-23). */
    class MediaUnavailableException extends RuntimeException {
        private final int status;

        public MediaUnavailableException(int status) {
            super("media unavailable status=" + status, null, false, false);
            this.status = status;
        }

        /** The provider's HTTP status, or 0 when the failure was not an HTTP answer (a timeout, a reset). */
        public int status() {
            return status;
        }
    }

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
     * part of the answer: completes empty on any failure, never errors, and is bounded in time
     * (WaIntakeService also bounds it, for any implementation), because the reply waits behind it.
     *
     * @param providerMessageId the inbound message's wamid
     */
    default Mono<Void> markRead(String providerMessageId, boolean typing) {
        return Mono.empty();
    }
}
