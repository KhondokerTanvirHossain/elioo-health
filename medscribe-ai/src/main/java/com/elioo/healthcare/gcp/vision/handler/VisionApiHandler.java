package com.elioo.healthcare.gcp.vision.handler;

import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.dto.*;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import lombok.RequiredArgsConstructor;
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
 * Handler for GCP Vision API web endpoints.
 *
 * <p>Provides reactive HTTP request handlers for Google Cloud Vision OCR operations:
 * <ul>
 *   <li>Simple text detection</li>
 *   <li>Comprehensive document analysis</li>
 *   <li>Image quality validation</li>
 * </ul>
 *
 * <p><b>Architecture:</b> This handler acts as the web layer adapter for the
 * VisionService, transforming HTTP requests into service calls and mapping
 * service responses to HTTP responses.</p>
 *
 * @since 1.0.0
 * @see VisionService
 * @see VisionApiRouter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VisionApiHandler {

    private final VisionService visionService;

    /**
     * Handle simple text detection requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/vision/detect-text}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA..."
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Simple text extraction with detected language and confidence.</p>
     *
     * @param request ServerRequest containing DetectTextRequest
     * @return Mono&lt;ServerResponse&gt; with DetectTextResponse or error
     */
    public Mono<ServerResponse> detectText(ServerRequest request) {
        return request.bodyToMono(DetectTextRequest.class)
                .doOnNext(req -> log.info("GCP Vision: Detecting text from image"))
                .flatMap(req -> visionService.detectText(req.imageBase64()))
                .map(text -> new DetectTextResponse(text, "auto-detected", 1.0))
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response))
                .onErrorResume(this::handleError);
    }

    /**
     * Handle comprehensive document analysis requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/vision/analyze-document}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
     *   "languageHints": ["bn", "en"]
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Structured OCR results with full text, blocks, paragraphs,
     * words, and geometry information.</p>
     *
     * @param request ServerRequest containing AnalyzeDocumentRequest
     * @return Mono&lt;ServerResponse&gt; with AnalyzeDocumentResponse or error
     */
    public Mono<ServerResponse> analyzeDocument(ServerRequest request) {
        return request.bodyToMono(AnalyzeDocumentRequest.class)
                .doOnNext(req -> log.info("GCP Vision: Analyzing document with language hints: {}",
                        req.languageHints()))
                .flatMap(req -> {
                    VisionOcrRequest visionRequest = new VisionOcrRequest(
                            req.imageBase64(),
                            req.languageHints(),
                            true,  // includeConfidence
                            true   // includeGeometry
                    );
                    return visionService.detectDocumentText(visionRequest);
                })
                .map(AnalyzeDocumentResponse::from)
                .flatMap(response -> {
                    log.info("GCP Vision: Document analyzed successfully. Blocks: {}, Confidence: {:.2f}",
                            response.blocks().size(), response.averageConfidence());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle image quality validation requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/vision/validate-image}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA..."
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Validation result with quality issues and metrics.</p>
     *
     * @param request ServerRequest containing ValidateImageRequest
     * @return Mono&lt;ServerResponse&gt; with ValidateImageResponse or error
     */
    public Mono<ServerResponse> validateImage(ServerRequest request) {
        return request.bodyToMono(ValidateImageRequest.class)
                .doOnNext(req -> log.info("GCP Vision: Validating image quality"))
                .flatMap(req -> visionService.validateImageQuality(req.imageBase64()))
                .map(result -> {
                    ValidateImageResponse response = new ValidateImageResponse(
                            result.isValid(),
                            result.isValid() ? List.of() : List.of(result.message()),
                            Map.of(
                                    "qualityScore", result.qualityScore(),
                                    "sizeBytes", result.getSizeInBytes(),
                                    "sizeMB", result.getSizeInMB()
                            )
                    );
                    log.info("GCP Vision: Image validation complete. Valid: {}, Quality: {:.2f}",
                            result.isValid(), result.qualityScore());
                    return response;
                })
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response))
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate HTTP error response.
     *
     * <p>Logs the error and returns a 500 Internal Server Error response with
     * error details in JSON format.</p>
     *
     * @param error The error that occurred
     * @return Mono&lt;ServerResponse&gt; with error details
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("GCP Vision API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "gcp-vision"
                ));
    }
}
