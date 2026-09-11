package com.elioo.healthcare.aws.textract.handler;

import com.elioo.healthcare.aws.textract.api.TextractService;
import com.elioo.healthcare.aws.textract.dto.AnalyzeDocumentRequest;
import com.elioo.healthcare.aws.textract.dto.AnalyzeDocumentResponse;
import com.elioo.healthcare.aws.textract.dto.DetectTextRequest;
import com.elioo.healthcare.aws.textract.dto.ValidateImageRequest;
import com.elioo.healthcare.aws.textract.model.OcrRequest;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.textract.model.FeatureType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Web handler for generic AWS Textract API endpoints.
 *
 * <p>Exposes direct access to AWS Textract services via REST endpoints:
 * <ul>
 *   <li>POST /api/aws/textract/analyze-document - Comprehensive document analysis with tables, forms, layout</li>
 *   <li>POST /api/aws/textract/detect-text - Simple text extraction</li>
 *   <li>POST /api/aws/textract/validate-image - Pre-processing quality validation</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TextractApiHandler {

    private final TextractService textractService;

    /**
     * Analyze document with comprehensive feature extraction.
     *
     * @param request ServerRequest containing AnalyzeDocumentRequest
     * @return ServerResponse with extracted blocks
     */
    public Mono<ServerResponse> analyzeDocument(ServerRequest request) {
        return request.bodyToMono(AnalyzeDocumentRequest.class)
                .doOnNext(req -> log.info("Analyzing document with features: {}", req.featureTypes()))
                .flatMap(req -> {
                    // Build OCR request from API request using factory method
                    OcrRequest ocrRequest;
                    if (req.featureTypes() != null && !req.featureTypes().isEmpty()) {
                        // Convert string feature types to FeatureType enum
                        List<FeatureType> features = req.featureTypes().stream()
                                .map(FeatureType::fromValue)
                                .toList();
                        ocrRequest = OcrRequest.withFeatures(req.imageBase64(), features);
                    } else {
                        // Use standard features (TABLES, FORMS, LAYOUT)
                        ocrRequest = OcrRequest.standard(req.imageBase64());
                    }

                    return textractService.analyzeDocument(ocrRequest);
                })
                .flatMap(response -> {
                    log.info("Document analysis completed. Blocks: {}", response.blocks().size());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(AnalyzeDocumentResponse.from(response));
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Detect text from image (simple extraction without structure).
     *
     * @param request ServerRequest containing DetectTextRequest
     * @return ServerResponse with extracted text
     */
    public Mono<ServerResponse> detectText(ServerRequest request) {
        return request.bodyToMono(DetectTextRequest.class)
                .doOnNext(req -> log.info("Detecting text from image"))
                .flatMap(req -> textractService.detectText(req.imageBase64()))
                .flatMap(text -> {
                    log.info("Text detection completed. Length: {} characters", text.length());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "text", text,
                                    "length", text.length()
                            ));
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Validate image quality before OCR processing.
     *
     * @param request ServerRequest containing ValidateImageRequest
     * @return ServerResponse with quality validation result
     */
    public Mono<ServerResponse> validateImage(ServerRequest request) {
        return request.bodyToMono(ValidateImageRequest.class)
                .doOnNext(req -> log.info("Validating image quality"))
                .flatMap(req -> textractService.validateImageQuality(req.imageBase64()))
                .flatMap(result -> {
                    log.info("Image validation completed. Valid: {}, Score: {}",
                            result.isValid(), result.qualityScore());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(result);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate error response.
     *
     * @param error The exception that occurred
     * @return ServerResponse with error details
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Textract API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "textract"
                ));
    }
}
