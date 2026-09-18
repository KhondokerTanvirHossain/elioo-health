package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The one job: hourly, idempotent — a trigger that has a row never fires again. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NudgeScheduler {

    private final NudgeUseCase nudges;
    private final BaymaxProperties properties;

    @Scheduled(cron = "${baymax.nudge.cron:0 5 * * * *}")
    public void hourly() {
        if (!properties.getNudge().isEnabled()) {
            return;
        }
        nudges.evaluateAll()
                .doOnNext(n -> log.info("[baymax] nudge evaluation done composed={}", n))
                .doOnError(e -> log.error("[baymax] nudge evaluation failed: {}", e.getMessage(), e))
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .subscribe();
    }
}
