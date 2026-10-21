package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.wa.application.port.in.InboundRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * F9a retention: a row from a number with no family can never be deleted on request, and the business number and this
 * repository are public, so anyone can add to the table. The dedupe needs a key only while Meta retries (7 days), so
 * those rows go after 8 (RUNBOOK, "The same WhatsApp message twice"). A family's rows stay until the family deletes them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaInboundRetentionJob {

    private final InboundRecoveryUseCase recovery;

    @Scheduled(cron = "${baymax.wa.inbound-purge-cron:0 40 3 * * *}")
    public void daily() {
        purge().subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /** One purge and its one log line: a count, or the class name on failure. */
    Mono<Long> purge() {
        return Mono.defer(recovery::purgeUnknownSenders)
                .doOnNext(n -> log.info("[baymax] wa inbound purge done unknownSenderRows={}", n))
                .onErrorResume(e -> {
                    log.error("[baymax] wa inbound purge failed error={}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
