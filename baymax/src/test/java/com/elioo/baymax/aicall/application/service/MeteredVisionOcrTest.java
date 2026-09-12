package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeteredVisionOcrTest {

    private final VisionService vision = mock(VisionService.class);
    private final AiCallLogPort port = mock(AiCallLogPort.class);
    private final VisionOcrResponse response = mock(VisionOcrResponse.class);

    @SuppressWarnings("unchecked")
    private MeteredVisionOcr metered(VisionService service, String pricePerImage) {
        ObjectProvider<VisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        BaymaxProperties props = new BaymaxProperties();
        if (pricePerImage != null) {
            props.getVision().setCostPerImage(new BigDecimal(pricePerImage));
        }
        when(port.save(any())).thenAnswer(inv -> Mono.just(((AiCallRecord) inv.getArgument(0)).withId(UUID.randomUUID())));
        return new MeteredVisionOcr(provider, port, new AiCostCalculator(props));
    }

    @Test
    void oneVisionCallWritesOneOcrRowWithImagePriceAndAverageConfidence() {
        when(vision.detectDocumentText(any())).thenReturn(Mono.just(response));
        when(response.averageConfidence()).thenReturn(0.87);
        UUID documentId = UUID.randomUUID();

        StepVerifier.create(metered(vision, "0.0015").detectDocumentText(documentId, VisionOcrRequest.standard("aGk=")))
                .expectNext(response)
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        AiCallRecord row = saved.getValue();
        assertThat(row.purpose()).isEqualTo(AiCallPurpose.OCR);
        assertThat(row.provider()).isEqualTo("gcp");
        assertThat(row.model()).isEqualTo("vision-document-text-detection");
        assertThat(row.documentId()).isEqualTo(documentId);
        assertThat(row.inputTokens()).isZero();
        assertThat(row.costUsd()).isEqualByComparingTo("0.0015");
        assertThat(row.confidence()).isEqualTo(0.87);
        assertThat(row.latencyMs()).isNotNull();
    }

    @Test
    void unpricedVisionIsLoggedWithNullCost() {
        when(vision.detectDocumentText(any())).thenReturn(Mono.just(response));
        when(response.averageConfidence()).thenReturn(0.0);

        StepVerifier.create(metered(vision, null).detectDocumentText(null, VisionOcrRequest.standard("aGk=")))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().costUsd()).isNull();
        assertThat(saved.getValue().confidence()).as("0.0 means Vision reported none").isNull();
    }

    @Test
    void missingVisionBeanFailsAtCallTimeWithoutARow() {
        StepVerifier.create(metered(null, "0.0015").detectDocumentText(null, VisionOcrRequest.standard("aGk=")))
                .expectError(IllegalStateException.class)
                .verify();
        verify(port, never()).save(any());
    }

    @Test
    void failedOcrWritesNoRow() {
        when(vision.detectDocumentText(any())).thenReturn(Mono.error(new RuntimeException("vision down")));

        StepVerifier.create(metered(vision, "0.0015").detectDocumentText(null, VisionOcrRequest.standard("aGk=")))
                .expectError(RuntimeException.class)
                .verify();
        verify(port, never()).save(any());
    }
}
