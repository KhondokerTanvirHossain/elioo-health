package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.textract.api.TextractService;
import com.elioo.healthcare.aws.textract.model.ExtractedBlock;
import com.elioo.healthcare.aws.textract.model.ImageQualityResult;
import com.elioo.healthcare.aws.textract.model.OcrResponse;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OCR Unit Test - Textract Adapter with TextractService Mocks")
class TextractAdapterTest {

    @Mock
    private TextractService textractService;

    private TextractAdapter textractAdapter;

    private static final String SAMPLE_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    @BeforeEach
    void setUp() {
        textractAdapter = new TextractAdapter(textractService);
    }

    @Test
    @DisplayName("Extract raw text successfully")
    void testExtractRawText_Success() {
        when(textractService.detectText(any()))
                .thenReturn(Mono.just("Hemoglobin: 14.5 g/dL (13-17)"));

        Mono<String> rawTextMono = textractAdapter.extractRawText(SAMPLE_IMAGE_BASE64, "en");

        StepVerifier.create(rawTextMono)
                .assertNext(rawText -> {
                    assertThat(rawText).contains("Hemoglobin");
                    assertThat(rawText).contains("14.5 g/dL");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Validate image quality - acceptable size")
    void testValidateImageQuality_AcceptableSize() {
        ImageQualityResult serviceResult = ImageQualityResult.valid(0.82, Map.of("sizeInMB", "1.00"));
        when(textractService.validateImageQuality(any())).thenReturn(Mono.just(serviceResult));

        Mono<OcrPort.ImageQualityResult> qualityMono =
                textractAdapter.validateImageQuality(SAMPLE_IMAGE_BASE64);

        StepVerifier.create(qualityMono)
                .assertNext(result -> {
                    assertThat(result.isValid()).isTrue();
                    assertThat(result.qualityScore()).isEqualTo(0.82);
                    assertThat(result.metrics()).containsEntry("sizeInMB", "1.00");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Validate image quality - too large")
    void testValidateImageQuality_TooLarge() {
        ImageQualityResult serviceResult = ImageQualityResult.invalid("Image size exceeds maximum allowed size", Map.of());
        when(textractService.validateImageQuality(any())).thenReturn(Mono.just(serviceResult));

        Mono<OcrPort.ImageQualityResult> qualityMono =
                textractAdapter.validateImageQuality(SAMPLE_IMAGE_BASE64);

        StepVerifier.create(qualityMono)
                .assertNext(result -> {
                    assertThat(result.isValid()).isFalse();
                    assertThat(result.reason()).contains("exceeds maximum");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Extract medical data from table structure")
    void testExtractMedicalData_WithTableData() {
        ExtractedBlock wordBlock = new ExtractedBlock(
                "word-1",
                "WORD",
                "Glucose: 95 mg/dL (70-100)",
                96.0f,
                List.of(),
                null,
                null,
                null,
                1
        );

        ExtractedBlock cellBlock = new ExtractedBlock(
                "cell-1",
                "CELL",
                null,
                95.0f,
                List.of(Relationship.builder()
                        .type(RelationshipType.CHILD)
                        .ids("word-1")
                        .build()),
                null,
                1,
                1,
                1
        );

        ExtractedBlock tableBlock = new ExtractedBlock(
                "table-1",
                "TABLE",
                null,
                92.0f,
                List.of(Relationship.builder()
                        .type(RelationshipType.CHILD)
                        .ids("cell-1")
                        .build()),
                null,
                null,
                null,
                1
        );

        OcrResponse ocrResponse = new OcrResponse(
                List.of(tableBlock, cellBlock, wordBlock),
                1,
                "SUCCEEDED",
                Map.of()
        );

        when(textractService.analyzeDocument(any())).thenReturn(Mono.just(ocrResponse));

        Flux<TestResult> results = textractAdapter.extractMedicalData(SAMPLE_IMAGE_BASE64, "BLOOD_TEST", Map.of());

        StepVerifier.create(results.collectList())
                .assertNext(list -> {
                    assertThat(list).isNotEmpty();
                    TestResult first = list.getFirst();
                    assertThat(first.getTestName()).isEqualTo("Glucose");
                    assertThat(first.getStatus()).isIn(TestStatus.NORMAL, TestStatus.ABNORMAL, TestStatus.CRITICAL);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Handle Textract error gracefully")
    void testExtractMedicalData_TextractError() {
        when(textractService.analyzeDocument(any())).thenReturn(Mono.error(new RuntimeException("Service down")));

        Flux<TestResult> resultFlux = textractAdapter.extractMedicalData(
                SAMPLE_IMAGE_BASE64,
                "BLOOD_TEST",
                Map.of()
        );

        StepVerifier.create(resultFlux)
                .expectError()
                .verify();
    }
}
