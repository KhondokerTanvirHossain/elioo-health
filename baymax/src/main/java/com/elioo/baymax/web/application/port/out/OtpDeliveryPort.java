package com.elioo.baymax.web.application.port.out;

import reactor.core.publisher.Mono;

/**
 * How a code reaches the family: over WhatsApp, as a free-form message inside the window the family's own message
 * opened (DR-39). Never a template, paid or otherwise. Never an error upstream: a failed send falls back to a log line
 * that carries no code above DEBUG.
 */
public interface OtpDeliveryPort {

    /** Emits true only when the provider accepted the message carrying the code; false for every other outcome. */
    Mono<Boolean> deliver(String whatsappNumber, String phoneHash, String code);
}
