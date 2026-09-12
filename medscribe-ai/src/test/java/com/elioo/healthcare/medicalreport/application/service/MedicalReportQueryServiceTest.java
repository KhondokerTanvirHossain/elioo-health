package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalReportQueryServiceTest {

    private final MedicalReportPersistencePort port = mock(MedicalReportPersistencePort.class);
    private final MedicalReportQueryService service = new MedicalReportQueryService(port);

    @Test
    void metricsOnAnEmptyDatabaseAreZerosNotAnError() {
        // fresh install: no rows, so AVG() is NULL and the reactive query completes empty
        when(port.countProcessesByStatus(any())).thenReturn(Mono.just(0L));
        when(port.getAverageProcessingTime(any())).thenReturn(Mono.empty());
        when(port.getStageStatistics(any())).thenReturn(Flux.empty());
        when(port.getErrorPatterns(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.getProcessingMetrics(LocalDateTime.now().minusDays(7)))
                .assertNext(m -> {
                    assertThat(m.totalProcessed()).isZero();
                    assertThat(m.successRate()).isZero();
                    assertThat(m.averageProcessingTimeMs()).isZero();
                    assertThat(m.stageMetrics()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void totalCountsEveryStatusIncludingInFlight() {
        when(port.countProcessesByStatus(ProcessingStatus.COMPLETED)).thenReturn(Mono.just(5L));
        when(port.countProcessesByStatus(ProcessingStatus.FAILED)).thenReturn(Mono.just(1L));
        when(port.countProcessesByStatus(ProcessingStatus.PARTIAL_SUCCESS)).thenReturn(Mono.just(2L));
        when(port.countProcessesByStatus(ProcessingStatus.IN_PROGRESS)).thenReturn(Mono.just(3L));
        when(port.countProcessesByStatus(ProcessingStatus.PENDING)).thenReturn(Mono.just(4L));
        when(port.getAverageProcessingTime(any())).thenReturn(Mono.just(1234.5));
        when(port.getStageStatistics(any())).thenReturn(Flux.empty());
        when(port.getErrorPatterns(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.getProcessingMetrics(LocalDateTime.now().minusDays(7)))
                .assertNext(m -> {
                    assertThat(m.totalProcessed()).isEqualTo(15L);
                    assertThat(m.completedCount()).isEqualTo(5L);
                    // success rate is over finished reports only: (5 + 2) / (5 + 1 + 2)
                    assertThat(m.successRate()).isCloseTo(87.5, org.assertj.core.data.Offset.offset(0.01));
                    assertThat(m.averageProcessingTimeMs()).isEqualTo(1234.5);
                })
                .verifyComplete();
    }
}
