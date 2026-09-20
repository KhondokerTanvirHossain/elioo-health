package com.elioo.baymax.wa.adapter.out;

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

import java.util.List;

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
 * <p>Nothing PENDING reaches here: {@code ReviewGateService} is the only caller and only for an explicit
 * approve or a released message.
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
        if (message.body() == null || message.body().isBlank()) {
            // a safety-check failure stores the row with no body; there is nothing to send and that is correct
            log.warn("[baymax] wa delivery skipped id={} — no body", message.id());
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
        List<String> allowlist = properties.getWa().getAllowlist();
        if (!allowlist.isEmpty() && allowlist.stream().noneMatch(a -> a.trim().equals(number))) {
            // enforced here, not left to the Meta test number's own restriction, which disappears in production
            log.warn("[baymax] wa delivery refused id={} — recipient not on BAYMAX_WA_ALLOWLIST (phase 1)", message.id());
            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.NOT_ALLOWLISTED));
        }
        return messaging.sendText(number, message.body())
                .map(wamid -> {
                    log.info("[baymax] wa delivered id={} wamid={} urgency={} chars={}",
                            message.id(), wamid, message.urgency(), message.body().length());
                    return DeliveryOutcome.sent(wamid);
                })
                .onErrorResume(e -> {
                    String code = classify(e);
                    log.error("[baymax] wa delivery failed id={} error={} : {}", message.id(), code, e.toString());
                    return Mono.just(DeliveryOutcome.failed(code));
                });
    }

    /**
     * Provider prose is never stored — the row carries a machine code. The two cases worth telling apart are a
     * closed session window (expected, and a latency signal) and a dead token (an operational alarm).
     */
    static String classify(Throwable e) {
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
