package com.elioo.baymax.extraction.adapter.in;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

/**
 * Starts the F9b recovery sweep once the application is ready, detached so the health check (deploy.sh waits 90 s)
 * never waits on model calls.
 *
 * <p>The cutoff is the instant this bean was BUILT, not the ready event: Netty serves the webhook before the ready
 * event, and a photo arriving in that gap is already being processed by this instance — a later cutoff would
 * process it twice.
 */
@Slf4j
@Component
public class DocumentRecoveryStartup {

    private final DocumentRecoveryUseCase recovery;
    private final BaymaxProperties properties;
    private final Instant cutoff;

    public DocumentRecoveryStartup(DocumentRecoveryUseCase recovery, BaymaxProperties properties, Clock clock) {
        this.recovery = recovery;
        this.properties = properties;
        this.cutoff = clock.instant();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.getRecovery().isEnabled()) {
            log.info("[baymax] recovery sweep disabled (baymax.recovery.enabled=false)");
            return;
        }
        recovery.recoverInterrupted(cutoff)
                .subscribeOn(Schedulers.boundedElastic())
                .count()
                .subscribe(n -> log.info("[baymax] recovery sweep done documents={}", n),
                        e -> log.error("[baymax] recovery sweep failed: {}", e.getClass().getSimpleName()));
    }
}
