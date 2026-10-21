package com.elioo.baymax.storage.application.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The process-wide bound under the read-path backfill (D4b): a permit per running work, given back however it ends. */
class FetchGateTest {

    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger most = new AtomicInteger();

    /**
     * A work that counts itself running from its subscription until it ends: the count drops as the work terminates,
     * before its result travels on (doFinally would drop it only after the gate has already started the next work).
     */
    private Mono<Integer> work(int n, Duration takes) {
        return Mono.defer(() -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            return Mono.delay(takes).thenReturn(n).doOnTerminate(running::decrementAndGet).doOnCancel(running::decrementAndGet);
        });
    }

    @Test
    void neverMoreThanTheLimitRunAtOnceAndEveryWorkRuns() {
        FetchGate gate = new FetchGate(2);

        List<Integer> done = Flux.range(0, 12)
                .flatMap(n -> gate.run(() -> work(n, Duration.ofMillis(30))).subscribeOn(Schedulers.parallel()), 12)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(most.get()).as("works running at once").isEqualTo(2);
        assertThat(done).as("every work's result").containsExactlyInAnyOrderElementsOf(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
    }

    @Test
    void aWorkCancelledWhileItWaitsNeverRunsAndHoldsNoPermit() {
        FetchGate gate = new FetchGate(1);
        Sinks.Empty<Void> first = Sinks.empty();
        AtomicBoolean waitedRan = new AtomicBoolean();
        Disposable holder = gate.run(() -> first.asMono().thenReturn(1)).subscribe();
        Disposable waiting = gate.run(() -> Mono.fromRunnable(() -> waitedRan.set(true)).thenReturn(2)).subscribe();

        waiting.dispose();
        first.tryEmitEmpty();

        assertThat(gate.run(() -> Mono.just(3)).block(Duration.ofSeconds(2))).as("a later work, after the cancelled one").isEqualTo(3);
        assertThat(waitedRan).as("the cancelled work ran").isFalse();
        holder.dispose();
    }

    @Test
    void aWorkCancelledWhileItRunsGivesItsPermitBack() {
        FetchGate gate = new FetchGate(1);
        Disposable stuck = gate.run(() -> Mono.<Integer>never()).subscribe();

        stuck.dispose();

        assertThat(gate.run(() -> Mono.just(7)).block(Duration.ofSeconds(2))).as("a work after the stuck one was cancelled").isEqualTo(7);
    }

    @Test
    void aWorkThatTimesOutGivesItsPermitBackAndAnEmptyWorkCompletesEmpty() {
        FetchGate gate = new FetchGate(1);
        Mono<Integer> late = gate.run(() -> Mono.<Integer>never()).timeout(Duration.ofMillis(50)).onErrorResume(e -> Mono.just(-1));

        assertThat(late.block(Duration.ofSeconds(2))).as("the timed-out work").isEqualTo(-1);
        assertThat(gate.run(Mono::<Integer>empty).blockOptional(Duration.ofSeconds(2))).as("an empty work").isEmpty();
        assertThat(gate.run(() -> Mono.<Integer>error(new IllegalStateException("x"))).onErrorResume(e -> Mono.just(-2))
                .block(Duration.ofSeconds(2))).as("a failed work").isEqualTo(-2);
        assertThat(gate.run(() -> Mono.just(9)).block(Duration.ofSeconds(2))).as("a work after all three").isEqualTo(9);
    }
}
