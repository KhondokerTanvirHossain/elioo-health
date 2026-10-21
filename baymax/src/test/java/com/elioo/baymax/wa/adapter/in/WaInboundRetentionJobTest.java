package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.wa.application.port.in.InboundRecoveryUseCase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The daily purge of unknown senders' keys (the SQL itself is on real Postgres, PostgresInboundMessageAdapterTest). */
class WaInboundRetentionJobTest {

    private final InboundRecoveryUseCase recovery = mock(InboundRecoveryUseCase.class);

    @Test
    void thePurgeRunsThroughTheUseCaseAndReturnsItsCount() {
        when(recovery.purgeUnknownSenders()).thenReturn(Mono.just(3L));
        assertThat(new WaInboundRetentionJob(recovery).purge().block()).isEqualTo(3L);
        verify(recovery).purgeUnknownSenders();
    }

    @Test
    void aFailedPurgeIsLoggedNeverThrown() {
        when(recovery.purgeUnknownSenders()).thenReturn(Mono.error(new IllegalStateException("db down")));
        assertThat(new WaInboundRetentionJob(recovery).purge().block()).isNull();
    }

    @Test
    void theRetentionOutlastsMetasSevenDaysOfRetries() {
        assertThat(InboundRecoveryUseCase.UNKNOWN_SENDER_RETENTION).isGreaterThan(java.time.Duration.ofDays(7));
    }
}
