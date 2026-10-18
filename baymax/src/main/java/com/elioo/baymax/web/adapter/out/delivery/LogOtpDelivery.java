package com.elioo.baymax.web.adapter.out.delivery;

import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The fallback when a code could not go over WhatsApp: it says so at WARN, against the phone HMAC and a machine
 * reason, and never with the code. The code is at DEBUG only, for break-glass on this logger
 * ({@code LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG}, RUNBOOK). Not a bean: only
 * {@link CompositeOtpDelivery} uses it. BMX-5 wrote the code at INFO here (DR-39 supersedes that).
 */
@Slf4j
public class LogOtpDelivery implements OtpDeliveryPort {

    @Override
    public Mono<Boolean> deliver(String whatsappNumber, String phoneHash, String code) {
        return deliver(whatsappNumber, phoneHash, code, "channel_off");
    }

    /** Always false: a log line is not a delivery, so nothing upstream may say "sent". */
    public Mono<Boolean> deliver(String whatsappNumber, String phoneHash, String code, String reason) {
        return Mono.fromCallable(() -> {
            log.warn("[baymax] otp NOT delivered phoneHash={} reason={} — ask again on WhatsApp; code at DEBUG only",
                    phoneHash, reason);
            log.debug("[baymax] otp fallback phoneHash={} code={}", phoneHash, code);
            return false;
        });
    }
}
