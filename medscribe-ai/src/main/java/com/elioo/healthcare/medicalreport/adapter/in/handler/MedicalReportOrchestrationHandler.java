package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportOrchestrationUseCase;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import com.elioo.healthcare.medicalreport.dto.ProcessingInitiatedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Web handler for master orchestration API.
 *
 * <p>Architecture: Inbound Adapter (Driving Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Converts HTTP requests to domain objects</li>
 *   <li>Delegates to use case (inbound port)</li>
 *   <li>Converts domain responses to HTTP responses</li>
 *   <li>No business logic - pure adapter layer</li>
 * </ul>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>HTTP request validation and parsing</li>
 *   <li>HTTP status code mapping</li>
 *   <li>Error response formatting</li>
 *   <li>Logging of requests and responses</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportOrchestrationHandler {

    private final MedicalReportOrchestrationUseCase orchestrationUseCase;

    /**
     * Handle master processing request (Async Version).
     *
     * <p>Endpoint: POST /api/v1/medical-report/process</p>
     *
     * <p>Request Body: {@link MasterProcessingRequest}</p>
     * <p>Response Body: {@link ProcessingInitiatedResponse}</p>
     *
     * <p><b>Async Processing Flow:</b></p>
     * <ol>
     *   <li>Validates image data (< 500ms)</li>
     *   <li>Creates process record in database with PENDING status</li>
     *   <li>Returns HTTP 202 Accepted with report ID immediately</li>
     *   <li>Processing continues in background (up to 10 minutes)</li>
     *   <li>Client polls status endpoints for progress updates</li>
     * </ol>
     *
     * <p>HTTP Status Codes:</p>
     * <ul>
     *   <li>202 Accepted - Processing initiated successfully (report ID returned)</li>
     *   <li>422 Unprocessable Entity - Image validation failed</li>
     *   <li>500 Internal Server Error - Failed to initiate processing</li>
     * </ul>
     *
     * <p><b>Status Polling Endpoints:</b></p>
     * <ul>
     *   <li>GET /api/v1/medical-report/query/status/{reportId} - Basic status</li>
     *   <li>GET /api/v1/medical-report/query/details/{reportId} - Detailed status with partial results</li>
     * </ul>
     *
     * @param request HTTP server request
     * @return Mono of HTTP 202 response with report ID
     */
    public Mono<ServerResponse> processCompleteMedicalReport(ServerRequest request) {
        return request.bodyToMono(MasterProcessingRequest.class)
                .doOnNext(masterRequest -> log.info(
                        "Received master processing request for patient: {}, image size: {} bytes",
                        masterRequest.getPatientContext().getPatientId(),
                        masterRequest.getImageBase64() != null ? masterRequest.getImageBase64().length() : 0
                ))
                .flatMap(masterRequest -> {
                    // Initiate async processing - returns report ID immediately
                    return orchestrationUseCase.initiateProcessing(masterRequest);
                })
                .flatMap(reportId -> {
                    log.info("Processing initiated successfully for report: {}", reportId);

                    // Build response with report ID and status URLs
                    ProcessingInitiatedResponse response = ProcessingInitiatedResponse.builder()
                            .reportId(reportId)
                            .status("PENDING")
                            .statusUrl("/api/v1/medical-report/query/status/" + reportId)
                            .detailedStatusUrl("/api/v1/medical-report/query/details/" + reportId)
                            .acceptedAt(LocalDateTime.now())
                            .estimatedCompletionTime(LocalDateTime.now().plusMinutes(10))
                            .build();

                    // Return HTTP 202 Accepted (async processing pattern)
                    return ServerResponse.status(202)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle multi-image processing request (Async Version).
     *
     * <p>Endpoint: POST /api/v1/medical-report/process-multi-image</p>
     *
     * <p>Request Body: {@link com.elioo.healthcare.medicalreport.dto.MultiImageRequest}</p>
     * <p>Response Body: {@link ProcessingInitiatedResponse}</p>
     *
     * <p><b>Async Processing Flow:</b></p>
     * <ol>
     *   <li>Validates all images (< 500ms)</li>
     *   <li>Creates process record in database with PENDING status</li>
     *   <li>Returns HTTP 202 Accepted with report ID immediately</li>
     *   <li>Processing continues in background (up to 15 minutes)</li>
     *   <li>Client polls status endpoints for progress updates</li>
     * </ol>
     *
     * <p><b>Multi-Image Workflow (Background):</b></p>
     * <ol>
     *   <li>Validate all images in parallel</li>
     *   <li>OCR all images in parallel</li>
     *   <li>Concatenate raw text and validate length (< 20,000 chars)</li>
     *   <li>Merge test results by confidence</li>
     *   <li>Run classification on concatenated text</li>
     *   <li>Generate clinical insights on merged results</li>
     * </ol>
     *
     * <p>HTTP Status Codes:</p>
     * <ul>
     *   <li>202 Accepted - Processing initiated successfully (report ID returned)</li>
     *   <li>422 Unprocessable Entity - Image validation failed</li>
     *   <li>500 Internal Server Error - Failed to initiate processing</li>
     * </ul>
     *
     * <p><b>Status Polling Endpoints:</b></p>
     * <ul>
     *   <li>GET /api/v1/medical-report/query/status/{reportId} - Basic status</li>
     *   <li>GET /api/v1/medical-report/query/details/{reportId} - Detailed status with partial results</li>
     * </ul>
     *
     * @param request HTTP server request
     * @return Mono of HTTP 202 response with report ID
     */
    public Mono<ServerResponse> processMultiImageReport(ServerRequest request) {
        return request.bodyToMono(com.elioo.healthcare.medicalreport.dto.MultiImageRequest.class)
                .doOnNext(multiImageRequest -> log.info(
                        "Received multi-image processing request: {} images, patient: {}",
                        multiImageRequest.getImages().size(),
                        multiImageRequest.getPatientContext().getPatientId()
                ))
                .flatMap(multiImageRequest -> {
                    // Initiate async processing - returns report ID immediately
                    return orchestrationUseCase.initiateMultiImageProcessing(multiImageRequest);
                })
                .flatMap(reportId -> {
                    log.info("Multi-image processing initiated successfully for report: {}", reportId);

                    // Build response with report ID and status URLs
                    ProcessingInitiatedResponse response = ProcessingInitiatedResponse.builder()
                            .reportId(reportId)
                            .status("PENDING")
                            .statusUrl("/api/v1/medical-report/query/status/" + reportId)
                            .detailedStatusUrl("/api/v1/medical-report/query/details/" + reportId)
                            .acceptedAt(LocalDateTime.now())
                            .estimatedCompletionTime(LocalDateTime.now().plusMinutes(15))  // Multi-image takes longer
                            .build();

                    // Return HTTP 202 Accepted (async processing pattern)
                    return ServerResponse.status(202)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and convert to appropriate HTTP responses.
     *
     * @param error The error that occurred
     * @return Mono of error response
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Error processing master request: {}", error.getMessage(), error);

        // Determine HTTP status code based on error type
        int statusCode = determineHttpStatusCode(error);

        ErrorResponse errorResponse = new ErrorResponse(
                determineErrorCode(error),
                error.getMessage(),
                Map.of(
                        "timestamp", LocalDateTime.now().toString(),
                        "errorType", error.getClass().getSimpleName()
                )
        );

        return ServerResponse.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(errorResponse);
    }

    /**
     * Determine HTTP status code based on error type.
     */
    private int determineHttpStatusCode(Throwable error) {
        String message = error.getMessage();
        if (message == null) return 500;

        String lowerMessage = message.toLowerCase();

        // Validation errors
        if (lowerMessage.contains("validation") || lowerMessage.contains("invalid")) {
            return 422; // Unprocessable Entity
        }

        // Timeout errors
        if (lowerMessage.contains("timeout")) {
            return 504; // Gateway Timeout
        }

        // Rate limiting
        if (lowerMessage.contains("rate limit") || lowerMessage.contains("throttl")) {
            return 429; // Too Many Requests
        }

        // Service unavailable
        if (lowerMessage.contains("unavailable")) {
            return 503; // Service Unavailable
        }

        // Default to internal server error
        return 500;
    }

    /**
     * Determine error code for response.
     */
    private String determineErrorCode(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "UNKNOWN_ERROR";

        String lowerMessage = message.toLowerCase();

        if (lowerMessage.contains("validation")) {
            return "VALIDATION_FAILED";
        } else if (lowerMessage.contains("timeout")) {
            return "TIMEOUT";
        } else if (lowerMessage.contains("rate limit") || lowerMessage.contains("throttl")) {
            return "RATE_LIMIT_EXCEEDED";
        } else if (lowerMessage.contains("unavailable")) {
            return "SERVICE_UNAVAILABLE";
        } else if (lowerMessage.contains("ocr")) {
            return "OCR_PROCESSING_FAILED";
        } else if (lowerMessage.contains("entity") || lowerMessage.contains("classification")) {
            return "ENTITY_DETECTION_FAILED";
        } else {
            return "PROCESSING_FAILED";
        }
    }

    /**
     * Error response DTO for API errors.
     *
     * @param code Error code (e.g., VALIDATION_FAILED, TIMEOUT)
     * @param message Human-readable error message
     * @param details Additional error details
     */
    public record ErrorResponse(
            String code,
            String message,
            Map<String, String> details
    ) {}
}
