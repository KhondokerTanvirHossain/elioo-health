package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportQueryUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Web handler for medical report query operations.
 *
 * <p>Architecture: Inbound Adapter (Web) in Hexagonal Architecture</p>
 * <ul>
 *   <li>HTTP layer for query APIs</li>
 *   <li>Converts HTTP requests to use case method calls</li>
 *   <li>Handles request validation and response formatting</li>
 *   <li>No business logic - pure translation layer</li>
 * </ul>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /status/{reportId} - Get processing status</li>
 *   <li>GET /details/{reportId} - Get complete processing details</li>
 *   <li>GET /patient/{patientId}/reports - Get all reports for patient</li>
 *   <li>GET /results/{reportId}/ocr - Get OCR results</li>
 *   <li>GET /results/{reportId}/classification - Get classification results</li>
 *   <li>GET /results/{reportId}/insights - Get clinical insights</li>
 *   <li>GET /results/{reportId}/all - Get all results</li>
 *   <li>GET /errors/{reportId} - Get report errors</li>
 *   <li>GET /analytics/metrics - Get processing metrics</li>
 *   <li>GET /analytics/stage-stats - Get stage statistics</li>
 *   <li>GET /analytics/error-patterns - Get error patterns</li>
 *   <li>GET /high-risk - Get high-risk reports</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportQueryHandler {

    private final MedicalReportQueryUseCase queryUseCase;

    // ==================== Process Status Queries ====================

    /**
     * GET /status/{reportId}
     * Get processing status summary for a report.
     */
    public Mono<ServerResponse> getProcessingStatus(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /status/{} - Getting processing status", reportId);

        return queryUseCase.getProcessingStatus(reportId)
                .flatMap(summary -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(summary))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /details/{reportId}
     * Get complete processing details including stages, errors, and results.
     */
    public Mono<ServerResponse> getProcessingDetails(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /details/{} - Getting processing details", reportId);

        return queryUseCase.getProcessingDetails(reportId)
                .flatMap(details -> {
                    // Collect all data into a single response
                    return details.stages().collectList()
                            .zipWith(details.errors().collectList())
                            .zipWith(details.results().collectList())
                            .map(tuple -> {
                                var stagesAndErrors = tuple.getT1();
                                var stages = stagesAndErrors.getT1();
                                var errors = stagesAndErrors.getT2();
                                var results = tuple.getT2();

                                return new ProcessingDetailsResponse(
                                        details.process(),
                                        stages,
                                        errors,
                                        results
                                );
                            })
                            .flatMap(response -> ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(response));
                })
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /patient/{patientId}/reports
     * Get all reports for a specific patient.
     */
    public Mono<ServerResponse> getPatientReports(ServerRequest request) {
        String patientId = request.pathVariable("patientId");
        log.info("GET /patient/{}/reports - Getting patient reports", patientId);

        return queryUseCase.getPatientReports(patientId)
                .collectList()
                .flatMap(reports -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(reports))
                .onErrorResume(this::handleError);
    }

    // ==================== Result Queries ====================

    /**
     * GET /results/{reportId}/ocr
     * Get OCR results for a report.
     */
    public Mono<ServerResponse> getOcrResults(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/ocr - Getting OCR results", reportId);

        return queryUseCase.getOcrResults(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/classification
     * Get classification results for a report.
     */
    public Mono<ServerResponse> getClassificationResults(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/classification - Getting classification results", reportId);

        return queryUseCase.getClassificationResults(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/icd10
     * Get ICD-10 code results for a report.
     */
    public Mono<ServerResponse> getIcd10Results(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/icd10 - Getting ICD-10 results", reportId);

        return queryUseCase.getIcd10Results(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/rxnorm
     * Get RxNorm code results for a report.
     */
    public Mono<ServerResponse> getRxNormResults(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/rxnorm - Getting RxNorm results", reportId);

        return queryUseCase.getRxNormResults(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/snomedct
     * Get SNOMED-CT code results for a report.
     */
    public Mono<ServerResponse> getSnomedCtResults(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/snomedct - Getting SNOMED-CT results", reportId);

        return queryUseCase.getSnomedCtResults(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/risk-assessment
     * Get risk assessment results for a report.
     */
    public Mono<ServerResponse> getRiskAssessment(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/risk-assessment - Getting risk assessment", reportId);

        return queryUseCase.getRiskAssessment(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/recommendations
     * Get recommendations for a report.
     */
    public Mono<ServerResponse> getRecommendations(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/recommendations - Getting recommendations", reportId);

        return queryUseCase.getRecommendations(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/educational-content
     * Get educational content for a report.
     */
    public Mono<ServerResponse> getEducationalContent(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/educational-content - Getting educational content", reportId);

        return queryUseCase.getEducationalContent(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/insights
     * Get clinical insights for a report.
     */
    public Mono<ServerResponse> getClinicalInsights(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/insights - Getting clinical insights", reportId);

        return queryUseCase.getClinicalInsights(reportId)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result))
                .switchIfEmpty(ServerResponse.notFound().build())
                .onErrorResume(this::handleError);
    }

    /**
     * GET /results/{reportId}/all
     * Get all results for a report.
     */
    public Mono<ServerResponse> getAllResults(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /results/{}/all - Getting all results", reportId);

        return queryUseCase.getAllResults(reportId)
                .collectList()
                .flatMap(results -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(results))
                .onErrorResume(this::handleError);
    }

    /**
     * GET /high-risk
     * Get high-risk reports requiring immediate attention.
     */
    public Mono<ServerResponse> getHighRiskReports(ServerRequest request) {
        log.info("GET /high-risk - Getting high-risk reports");

        return queryUseCase.getHighRiskReports()
                .collectList()
                .flatMap(reports -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(reports))
                .onErrorResume(this::handleError);
    }

    // ==================== Error Queries ====================

    /**
     * GET /errors/{reportId}
     * Get all errors for a specific report.
     */
    public Mono<ServerResponse> getReportErrors(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        log.info("GET /errors/{} - Getting report errors", reportId);

        return queryUseCase.getReportErrors(reportId)
                .collectList()
                .flatMap(errors -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(errors))
                .onErrorResume(this::handleError);
    }

    /**
     * GET /errors/recent?since=2024-01-01T00:00:00
     * Get recent errors for monitoring.
     */
    public Mono<ServerResponse> getRecentErrors(ServerRequest request) {
        String sinceParam = request.queryParam("since")
                .orElse(LocalDateTime.now().minusHours(24).toString());

        log.info("GET /errors/recent?since={} - Getting recent errors", sinceParam);

        try {
            LocalDateTime since = LocalDateTime.parse(sinceParam);

            return queryUseCase.getRecentErrors(since)
                    .collectList()
                    .flatMap(errors -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(errors))
                    .onErrorResume(this::handleError);
        } catch (DateTimeParseException e) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid date format. Use ISO-8601 format: 2024-01-01T00:00:00");
        }
    }

    // ==================== Analytics Queries ====================

    /**
     * GET /analytics/metrics?since=2024-01-01T00:00:00
     * Get processing metrics for monitoring dashboard.
     */
    public Mono<ServerResponse> getProcessingMetrics(ServerRequest request) {
        String sinceParam = request.queryParam("since")
                .orElse(LocalDateTime.now().minusDays(7).toString());

        log.info("GET /analytics/metrics?since={} - Getting processing metrics", sinceParam);

        try {
            LocalDateTime since = LocalDateTime.parse(sinceParam);

            return queryUseCase.getProcessingMetrics(since)
                    .flatMap(metrics -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(metrics))
                    .onErrorResume(this::handleError);
        } catch (DateTimeParseException e) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid date format. Use ISO-8601 format: 2024-01-01T00:00:00");
        }
    }

    /**
     * GET /analytics/stage-stats?since=2024-01-01T00:00:00
     * Get stage performance statistics.
     */
    public Mono<ServerResponse> getStageStatistics(ServerRequest request) {
        String sinceParam = request.queryParam("since")
                .orElse(LocalDateTime.now().minusDays(7).toString());

        log.info("GET /analytics/stage-stats?since={} - Getting stage statistics", sinceParam);

        try {
            LocalDateTime since = LocalDateTime.parse(sinceParam);

            return queryUseCase.getStageStatistics(since)
                    .collectList()
                    .flatMap(stats -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(stats))
                    .onErrorResume(this::handleError);
        } catch (DateTimeParseException e) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid date format. Use ISO-8601 format: 2024-01-01T00:00:00");
        }
    }

    /**
     * GET /analytics/error-patterns?since=2024-01-01T00:00:00
     * Get error patterns for debugging.
     */
    public Mono<ServerResponse> getErrorPatterns(ServerRequest request) {
        String sinceParam = request.queryParam("since")
                .orElse(LocalDateTime.now().minusDays(7).toString());

        log.info("GET /analytics/error-patterns?since={} - Getting error patterns", sinceParam);

        try {
            LocalDateTime since = LocalDateTime.parse(sinceParam);

            return queryUseCase.getErrorPatterns(since)
                    .collectList()
                    .flatMap(patterns -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(patterns))
                    .onErrorResume(this::handleError);
        } catch (DateTimeParseException e) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid date format. Use ISO-8601 format: 2024-01-01T00:00:00");
        }
    }

    // ==================== Error Handling ====================

    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Query handler error: {}", error.getMessage(), error);

        return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ErrorResponse(
                        "QUERY_ERROR",
                        error.getMessage(),
                        LocalDateTime.now()
                ));
    }

    // ==================== DTOs ====================

    /**
     * Response wrapper for processing details.
     */
    record ProcessingDetailsResponse(
            Object process,
            Object stages,
            Object errors,
            Object results
    ) {}

    /**
     * Error response.
     */
    record ErrorResponse(
            String errorCode,
            String message,
            LocalDateTime timestamp
    ) {}
}
