package com.elioo.baymax.outbound.adapter.in;

import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * F1: assesses, with nobody uploading, every DONE document whose stored urgency is missing or behind the date. Detached
 * from the caller, so the health check (deploy.sh waits 90 s) never waits on it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrgencySweepJob {

    private final DocumentUrgencyUseCase urgency;

    /** Backfill: DONE documents V18 left NULL (never messaged) are assessed by code once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        sweep("startup").subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /** follow_up_past_due is date-driven (UrgencyService's follow-up rule): the level can rise with nobody uploading. */
    @Scheduled(cron = "${baymax.urgency.sweep-cron:0 20 * * * *}")
    public void hourly() {
        sweep("hourly").subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /** One sweep and its one log line: a count on success, the class name on failure; never a value or a reason. */
    Mono<Long> sweep(String trigger) {
        return urgency.sweep()
                .doOnNext(n -> log.info("[baymax] urgency sweep done trigger={} documents={}", trigger, n))
                .doOnError(e -> log.error("[baymax] urgency sweep failed trigger={} error={}", trigger, e.getClass().getSimpleName()))
                .onErrorResume(e -> Mono.empty());
    }
}
