package com.elioo.baymax.wa.adapter.out;

import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Delivers an approved message over WhatsApp (BMX-10 phase 1), replacing the log adapter when the channel is
 * on. {@code @Primary} so it wins the injection point; with the flag unset this bean does not exist and
 * {@code LogMessageDelivery} is the only candidate, unchanged.
 *
 * <p>Session messages only: valid inside the 24-hour window the family's own upload opened. Phase 1 has no
 * approved templates, so a closed window is a real outcome — recorded as {@code window_expired} and left
 * undelivered rather than retried blind. If that turns out to be common it is a finding about review latency,
 * which is what the pilot needs to learn before the gate opens to families.
 *
 * <p>Nothing unreviewed is sent from here, whichever caller hands it over (F4): a row that is not RELEASED or
 * APPROVED with a body is refused with {@code not_released} and logged at ERROR. This used to be a claim
 * ("ReviewGateService is the only caller") while {@code WaIntakeService.onText} sent bodies around it; the rule
 * now lives where every release path — explanation, approve, nudges — meets it.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WhatsAppMessageDelivery implements MessageDeliveryPort {

    private final WaMessagingPort messaging;
    private final HealthRecordPort records;
    private final BaymaxProperties properties;

    @Override
    public Mono<DeliveryOutcome> deliver(OutboundMessage message) {
        if (!message.isDeliverable()) {
            // DR-13 / F4: only RELEASED or APPROVED rows with a body reach a family, whichever caller handed it over
            log.error("[baymax] wa delivery refused id={} gate={} — not released or approved", message.id(),
                    message.gateStatus() == null ? null : message.gateStatus().dbValue());
            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.NOT_RELEASED));
        }
        if (message.body().isBlank()) {
            // nothing to send; a blank body is never a message
            log.warn("[baymax] wa delivery skipped id={} — blank body", message.id());
            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED));
        }
        return records.findFamily(message.familyId())
                .flatMap(family -> send(message, family.whatsappNumber()))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    log.error("[baymax] wa delivery failed id={} — family not found", message.id());
                    return DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED);
                }));
    }

    private Mono<DeliveryOutcome> send(OutboundMessage message, String number) {
        if (!properties.getWa().permits(number)) {
            // enforced here, not left to the Meta test number's own restriction, which disappears in production
            log.warn("[baymax] wa delivery refused id={} — recipient not on BAYMAX_WA_ALLOWLIST (phase 1)", message.id());
            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.NOT_ALLOWLISTED));
        }
        return messaging.sendText(number, message.body())
                .map(wamid -> {
                    log.info("[baymax] wa delivered id={} ref={} urgency={} chars={}",
                            message.id(), Redact.id(wamid), message.urgency(), message.body().length());
                    return DeliveryOutcome.sent(wamid);
                })
                .onErrorResume(e -> {
                    String code = classify(e);
                    log.error("[baymax] wa delivery failed id={} error={} cause={}", message.id(), code,
                            e.getClass().getSimpleName());
                    return Mono.just(DeliveryOutcome.failed(code));
                });
    }

    /**
     * Provider prose is never stored — the row carries a machine code. The two cases worth telling apart are a
     * closed session window (expected, and a latency signal) and a dead token (an operational alarm). Public: the
     * login-code delivery classifies its failures the same way.
     */
    public static String classify(Throwable e) {
        String text = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("re-engagement") || text.contains("24 hour") || text.contains("24-hour")
                || text.contains("outside the allowed window") || text.contains("131047")) {
            return DeliveryOutcome.WINDOW_EXPIRED;
        }
        if (text.contains("access token") || text.contains("oauth") || text.contains("190")
                || text.contains("unauthorized") || text.contains("401")) {
            return DeliveryOutcome.TOKEN_INVALID;
        }
        return DeliveryOutcome.SEND_FAILED;
    }
}
