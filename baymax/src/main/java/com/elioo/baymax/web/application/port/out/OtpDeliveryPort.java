package com.elioo.baymax.web.application.port.out;

import reactor.core.publisher.Mono;

/**
 * How a code reaches the family. v1 ships one adapter, {@code log}, which writes the code to the server
 * log against the phone HMAC — never the number. WhatsApp delivery is BMX-10.
 */
public interface OtpDeliveryPort {

    Mono<Void> deliver(String whatsappNumber, String phoneHash, String code);
}
