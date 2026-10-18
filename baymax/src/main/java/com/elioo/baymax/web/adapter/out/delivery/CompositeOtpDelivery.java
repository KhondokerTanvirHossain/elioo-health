package com.elioo.baymax.web.adapter.out.delivery;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The one {@link OtpDeliveryPort} (DR-39). WhatsApp first, as a free-form session message ({@code sendText}) inside
 * the window the family's own message opened — never a template, which is paid. When the channel is off, the number
 * is not allowlisted, or the send fails, {@link LogOtpDelivery} says so with no code above DEBUG.
 *
 * <p>Emits true only when WhatsApp returned a message id: the sign-in page says "sent" on that and nothing else.
 * Logs carry the phone HMAC and a machine reason only — never the code, the number, the body, or the wamid (a wamid
 * is the number in base64).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeOtpDelivery implements OtpDeliveryPort {

    private final ObjectProvider<WaMessagingPort> whatsapp;
    private final Copy copy;
    private final BaymaxProperties properties;
    private final LogOtpDelivery fallback = new LogOtpDelivery();

    @Override
    public Mono<Boolean> deliver(String whatsappNumber, String phoneHash, String code) {
        WaMessagingPort wa = whatsapp.getIfAvailable();
        if (wa == null) {
            return fallback.deliver(whatsappNumber, phoneHash, code, "channel_off");
        }
        if (!properties.getWa().permits(whatsappNumber)) {
            return fallback.deliver(whatsappNumber, phoneHash, code, "not_allowlisted");
        }
        String body = copy.bn("wa.login_code", Map.of("code", code,
                "minutes", BanglaDate.digits(String.valueOf(properties.getAuth().getOtpTtl().toMinutes()))));
        return Mono.defer(() -> wa.sendText(whatsappNumber, body))
                .filter(wamid -> wamid != null && !wamid.isBlank())   // no id, no acceptance, whatever the port emits
                .map(wamid -> {
                    log.info("[baymax] otp delivered channel=whatsapp phoneHash={}", phoneHash);   // no wamid: it is the number
                    return true;
                })
                .switchIfEmpty(Mono.defer(() -> fallback.deliver(whatsappNumber, phoneHash, code, "no_message_id")))
                .onErrorResume(e -> {
                    log.debug("[baymax] otp send error: {}", e.getClass().getSimpleName());
                    return fallback.deliver(whatsappNumber, phoneHash, code, WhatsAppMessageDelivery.classify(e));
                });
    }
}
