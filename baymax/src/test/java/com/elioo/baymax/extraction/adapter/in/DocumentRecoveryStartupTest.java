package com.elioo.baymax.extraction.adapter.in;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRecoveryStartupTest {

    /**
     * The clock moves between construction and the ready event, as it does on a real boot (Netty serves the webhook in
     * that gap). A fixed clock would make "read at build" and "read at ready" the same instant, and this test could
     * not tell them apart.
     */
    @Test
    void theCutoffIsTheInstantTheBeanWasBuiltNotWhenTheAppBecameReady() {
        Instant built = Instant.parse("2026-10-17T06:00:00Z");
        Instant ready = built.plusSeconds(5);
        AtomicReference<Instant> now = new AtomicReference<>(built);
        Clock moving = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        DocumentRecoveryUseCase recovery = mock(DocumentRecoveryUseCase.class);
        when(recovery.recoverInterrupted(any())).thenReturn(Flux.empty());
        DocumentRecoveryStartup startup = new DocumentRecoveryStartup(recovery, new BaymaxProperties(), moving);

        now.set(ready);      // a photo can arrive now and is this instance's own work
        startup.onReady();

        verify(recovery, timeout(2000)).recoverInterrupted(built);
        verify(recovery, never()).recoverInterrupted(ready);
    }

    @Test
    void disabledMeansNoSweep() {
        BaymaxProperties p = new BaymaxProperties();
        p.getRecovery().setEnabled(false);
        DocumentRecoveryUseCase recovery = mock(DocumentRecoveryUseCase.class);
        new DocumentRecoveryStartup(recovery, p, Clock.systemUTC()).onReady();
        verify(recovery, after(500).never()).recoverInterrupted(any());
    }
}
