package com.elioo.healthcare.aws.textract.service;

import com.elioo.healthcare.aws.common.exception.AwsValidationException;
import com.elioo.healthcare.aws.textract.config.TextractProperties;
import com.elioo.healthcare.aws.textract.model.ImageQualityResult;
import com.elioo.healthcare.aws.textract.model.OcrRequest;
import com.elioo.healthcare.aws.textract.model.OcrResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.textract.TextractAsyncClient;
import software.amazon.awssdk.services.textract.model.*;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TextractServiceImpl Test")
class TextractServiceImplTest {

    @Mock
    private TextractAsyncClient textractClient;

    private TextractProperties properties;
    private TextractServiceImpl textractService;

    @BeforeEach
    void setUp() {
        properties = new TextractProperties();
        properties.setMinConfidenceThreshold(0.80);
        properties.setMaxImageSizeMb(10);
        properties.setMinImageSizeMb(0.1);

        textractService = new TextractServiceImpl(textractClient, properties);
    }

    @Test
    @DisplayName("Should analyze document and return OCR response")
    void testAnalyzeDocument() {
        // Given: Mock Textract response
        Block block1 = Block.builder()
                .id("block-1")
                .blockType(BlockType.LINE)
                .text("Test Result: 125 mg/dL")
                .confidence(0.95f)
                .page(1)
                .build();

        Block block2 = Block.builder()
                .id("block-2")
                .blockType(BlockType.TABLE)
                .confidence(0.90f)
                .page(1)
                .build();

        AnalyzeDocumentResponse awsResponse = AnalyzeDocumentResponse.builder()
                .blocks(block1, block2)
                .documentMetadata(DocumentMetadata.builder().pages(1).build())
                .analyzeDocumentModelVersion("1.0")
                .build();

        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(awsResponse));

        // When: Analyze document
        String testImage = Base64.getEncoder().encodeToString("test-image".getBytes());
        OcrRequest request = OcrRequest.standard(testImage);
        Mono<OcrResponse> responseMono = textractService.analyzeDocument(request);

        // Then: Verify response
        StepVerifier.create(responseMono)
                .assertNext(response -> {
                    assertThat(response).isNotNull();
                    assertThat(response.getBlockCount()).isEqualTo(2);
                    assertThat(response.getLines()).hasSize(1);
                    assertThat(response.getTables()).hasSize(1);
                    assertThat(response.documentPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should detect plain text from document")
    void testDetectText() {
        // Given: Mock text detection response
        Block line1 = Block.builder()
                .blockType(BlockType.LINE)
                .text("Patient Name: John Doe")
                .build();

        Block line2 = Block.builder()
                .blockType(BlockType.LINE)
                .text("Test Date: 2024-01-15")
                .build();

        DetectDocumentTextResponse awsResponse = DetectDocumentTextResponse.builder()
                .blocks(line1, line2)
                .build();

        when(textractClient.detectDocumentText(any(DetectDocumentTextRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(awsResponse));

        // When: Detect text
        String testImage = Base64.getEncoder().encodeToString("test-image".getBytes());
        Mono<String> textMono = textractService.detectText(testImage);

        // Then: Verify text extraction
        StepVerifier.create(textMono)
                .assertNext(text -> {
                    assertThat(text).isNotBlank();
                    assertThat(text).contains("Patient Name: John Doe");
                    assertThat(text).contains("Test Date: 2024-01-15");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate image quality - valid image")
    void testValidateImageQuality_Valid() {
        // Given: 1 MB image (valid)
        String validImage = Base64.getEncoder().encodeToString("A".repeat(1_048_576).getBytes());

        // When: Validate quality
        Mono<ImageQualityResult> resultMono = textractService.validateImageQuality(validImage);

        // Then: Should pass validation
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.isValid()).isTrue();
                    assertThat(result.qualityScore()).isGreaterThan(0.8);
                    assertThat(result.message()).contains("acceptable");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate image quality - too large")
    void testValidateImageQuality_TooLarge() {
        // Given: 11 MB image (exceeds limit)
        String largeImage = Base64.getEncoder().encodeToString("A".repeat(11_534_336).getBytes());

        // When: Validate quality
        Mono<ImageQualityResult> resultMono = textractService.validateImageQuality(largeImage);

        // Then: Should fail validation
        StepVerifier.create(resultMono)
                .expectError(AwsValidationException.class)
                .verify();
    }

    @Test
    @DisplayName("Should validate image quality - too small (warning)")
    void testValidateImageQuality_TooSmall() {
        // Given: 50 KB image (below recommended minimum)
        String smallImage = Base64.getEncoder().encodeToString("A".repeat(51_200).getBytes());

        // When: Validate quality
        Mono<ImageQualityResult> resultMono = textractService.validateImageQuality(smallImage);

        // Then: Should return warning (valid but low quality)
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.isValid()).isTrue();
                    assertThat(result.qualityScore()).isLessThan(0.5);
                    assertThat(result.message()).containsIgnoringCase("below recommended minimum");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should create OCR request with standard features")
    void testOcrRequest_Standard() {
        // When: Create standard request
        OcrRequest request = OcrRequest.standard("base64-image");

        // Then: Should have standard features
        assertThat(request.imageBase64()).isEqualTo("base64-image");
        assertThat(request.featureTypes()).contains(
                FeatureType.TABLES,
                FeatureType.FORMS,
                FeatureType.LAYOUT
        );
        assertThat(request.language()).isEqualTo("en");
    }

    @Test
    @DisplayName("Should create OCR request for text only")
    void testOcrRequest_TextOnly() {
        // When: Create text-only request
        OcrRequest request = OcrRequest.textOnly("base64-image");

        // Then: Should have no feature types
        assertThat(request.imageBase64()).isEqualTo("base64-image");
        assertThat(request.featureTypes()).isEmpty();
    }
}
