package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

/**
 * F9a's open gap, made visible. The webhook claims a message before it answers Meta, and handles it afterwards, detached;
 * a restart between the two (a deploy, a crash) leaves a claimed message that nobody answers, and Meta will not re-deliver
 * it because it got its 200. Re-dispatching those is F9c (Phase 4). Until then startup counts them, at WARN, so Tanvir
 * can tell whether that work is urgent.
 *
 * <p>The cutoff is this instance's construction time: anything received after it is in flight here, not cut off.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaInboundStartupReport {

    private final InboundMessagePort inbound;
    private final Instant cutoff;

    public WaInboundStartupReport(InboundMessagePort inbound, Clock clock) {
        this.inbound = inbound;
        this.cutoff = clock.instant();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        report().subscribeOn(Schedulers.boundedElastic()).subscribe(n -> { }, e -> { });
    }

    /** The count, logged at WARN when there is anything to count. A failure is logged, never thrown at startup. */
    Mono<Long> report() {
        return Mono.defer(() -> inbound.countUnhandledBefore(cutoff))
                .doOnNext(n -> {
                    if (n > 0) {
                        log.warn("[baymax] {} WhatsApp message(s) were received but never answered before this restart "
                                + "(re-dispatch is F9c; each one is a family that heard nothing)", n);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("[baymax] wa inbound startup count unavailable error={}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
