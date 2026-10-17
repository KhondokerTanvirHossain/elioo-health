package com.elioo.baymax.extraction.adapter.in;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRecoveryStartupTest {

    @Test
    void theCutoffIsTheInstantTheBeanWasBuiltNotWhenTheAppBecameReady() {
        Instant built = Instant.parse("2026-10-17T06:00:00Z");
        DocumentRecoveryUseCase recovery = mock(DocumentRecoveryUseCase.class);
        when(recovery.recoverInterrupted(any())).thenReturn(Flux.empty());
        DocumentRecoveryStartup startup = new DocumentRecoveryStartup(recovery, new BaymaxProperties(),
                Clock.fixed(built, ZoneOffset.UTC));

        startup.onReady();   // Netty has been serving the webhook since before this event

        verify(recovery, timeout(2000)).recoverInterrupted(built);
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
