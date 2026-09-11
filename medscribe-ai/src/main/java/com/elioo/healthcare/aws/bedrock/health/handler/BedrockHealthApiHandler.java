package com.elioo.healthcare.aws.bedrock.health.handler;

import com.elioo.healthcare.aws.bedrock.health.api.BedrockHealthService;
import com.elioo.healthcare.aws.bedrock.health.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Web handler for AWS Bedrock Health API endpoints.
 *
 * <p>Exposes domain-specific health AI capabilities via REST endpoints:
 * <ul>
 *   <li>POST /api/aws/bedrock/health/clinical-insights - Generate comprehensive clinical insights</li>
 *   <li>POST /api/aws/bedrock/health/summary - Generate patient or provider summaries</li>
 *   <li>POST /api/aws/bedrock/health/risk-assessment - Assess health risks</li>
 *   <li>POST /api/aws/bedrock/health/recommendations - Generate evidence-based recommendations</li>
 *   <li>POST /api/aws/bedrock/health/trend-analysis - Analyze historical health trends</li>
 *   <li>POST /api/aws/bedrock/health/educational-content - Generate patient education materials</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BedrockHealthApiHandler {

    private final BedrockHealthService bedrockHealthService;

    /**
     * Generate comprehensive clinical insights from medical data.
     *
     * @param request ServerRequest containing ClinicalInsightRequest
     * @return ServerResponse with clinical insights
     */
    public Mono<ServerResponse> generateClinicalInsights(ServerRequest request) {
        return request.bodyToMono(ClinicalInsightRequest.class)
                .doOnNext(req -> log.info("Generating clinical insights"))
                .flatMap(bedrockHealthService::generateClinicalInsights)
                .flatMap(response -> {
                    log.info("Clinical insights generated. Findings: {}, Recommendations: {}",
                            response.keyFindings() != null ? response.keyFindings().size() : 0,
                            response.recommendations() != null ? response.recommendations().size() : 0);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Generate summary of medical data.
     *
     * @param request ServerRequest containing SummaryRequest
     * @return ServerResponse with summary
     */
    public Mono<ServerResponse> generateSummary(ServerRequest request) {
        return request.bodyToMono(SummaryRequest.class)
                .doOnNext(req -> log.info("Generating summary for audience: {}",
                        req.options() != null ? req.options().targetAudience() : "PATIENT"))
                .flatMap(bedrockHealthService::generateSummary)
                .flatMap(response -> {
                    log.info("Summary generated. Key points: {}",
                            response.keyPoints() != null ? response.keyPoints().size() : 0);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Assess health risks from medical data.
     *
     * @param request ServerRequest containing RiskAssessmentRequest
     * @return ServerResponse with risk assessment
     */
    public Mono<ServerResponse> assessRisk(ServerRequest request) {
        return request.bodyToMono(RiskAssessmentRequest.class)
                .doOnNext(req -> log.info("Assessing health risks"))
                .flatMap(bedrockHealthService::assessRisk)
                .flatMap(response -> {
                    log.info("Risk assessment completed. Overall level: {}",
                            response.riskAssessment() != null ?
                                    response.riskAssessment().overallRiskLevel() : "UNKNOWN");
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Generate evidence-based clinical recommendations.
     *
     * @param request ServerRequest containing RecommendationRequest
     * @return ServerResponse with recommendations
     */
    public Mono<ServerResponse> generateRecommendations(ServerRequest request) {
        return request.bodyToMono(RecommendationRequest.class)
                .doOnNext(req -> log.info("Generating clinical recommendations"))
                .flatMap(bedrockHealthService::generateRecommendations)
                .flatMap(response -> {
                    log.info("Recommendations generated. Count: {}, Urgent: {}",
                            response.getRecommendationCount(), response.getUrgentCount());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Analyze historical health trends.
     *
     * @param request ServerRequest containing TrendAnalysisRequest
     * @return ServerResponse with trend analysis
     */
    public Mono<ServerResponse> analyzeTrends(ServerRequest request) {
        return request.bodyToMono(TrendAnalysisRequest.class)
                .doOnNext(req -> log.info("Analyzing health trends for {} data points",
                        req.getDataPointCount()))
                .flatMap(bedrockHealthService::analyzeTrends)
                .flatMap(response -> {
                    log.info("Trend analysis completed. Patterns found: {}, Anomalies: {}",
                            response.patterns() != null ? response.patterns().size() : 0,
                            response.anomalies() != null ? response.anomalies().size() : 0);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Generate patient education materials.
     *
     * @param request ServerRequest containing EducationalContentRequest
     * @return ServerResponse with educational content
     */
    public Mono<ServerResponse> generateEducationalContent(ServerRequest request) {
        return request.bodyToMono(EducationalContentRequest.class)
                .doOnNext(req -> log.info("Generating educational content for topic: {}", req.topic()))
                .flatMap(bedrockHealthService::generateEducationalContent)
                .flatMap(response -> {
                    log.info("Educational content generated. Title: {}", response.title());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
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
        log.error("Bedrock Health API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "bedrock-health"
                ));
    }
}
