package com.elioo.baymax.web.adapter.out.delivery;

import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * v1 delivery: the code goes to the server log and nowhere else. The line carries the phone HMAC so an
 * operator can match it to a request without the number ever being written. WhatsApp delivery is BMX-10;
 * it will be another implementation of the same port selected by {@code baymax.auth.otp-delivery}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "baymax.auth", name = "otp-delivery", havingValue = "log", matchIfMissing = true)
public class LogOtpDelivery implements OtpDeliveryPort {

    @Override
    public Mono<Void> deliver(String whatsappNumber, String phoneHash, String code) {
        return Mono.fromRunnable(() -> log.info("[baymax] OTP for phoneHash={} code={} (log delivery, BMX-5 v1)", phoneHash, code));
    }
}
