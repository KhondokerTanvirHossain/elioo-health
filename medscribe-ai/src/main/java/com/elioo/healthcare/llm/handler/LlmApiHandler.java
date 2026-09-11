package com.elioo.healthcare.llm.handler;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.dto.InvokeRequest;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.ClinicalInsightRequest;
import com.elioo.healthcare.llm.health.dto.EducationalContentRequest;
import com.elioo.healthcare.llm.health.dto.RecommendationRequest;
import com.elioo.healthcare.llm.health.dto.RiskAssessmentRequest;
import com.elioo.healthcare.llm.health.dto.SummaryRequest;
import com.elioo.healthcare.llm.health.dto.TrendAnalysisRequest;
import com.elioo.healthcare.llm.model.LlmRequest;
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
 * Raw access to the configured LLM provider and the clinical prompt layer.
 *
 * <ul>
 *   <li>POST /api/llm/invoke - send a prompt to the active provider</li>
 *   <li>POST /api/llm/health/clinical-insights</li>
 *   <li>POST /api/llm/health/summary</li>
 *   <li>POST /api/llm/health/risk-assessment</li>
 *   <li>POST /api/llm/health/recommendations</li>
 *   <li>POST /api/llm/health/trend-analysis</li>
 *   <li>POST /api/llm/health/educational-content</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmApiHandler {

    private final LlmClient llmClient;
    private final HealthInsightService healthInsightService;

    public Mono<ServerResponse> invoke(ServerRequest request) {
        return request.bodyToMono(InvokeRequest.class)
                .map(r -> new LlmRequest(r.userPrompt(), r.systemPrompt(), r.modelId(), r.maxTokens(),
                        r.temperature(), null, null, null, Boolean.TRUE.equals(r.jsonOutput())))
                .flatMap(llmClient::invoke)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> generateClinicalInsights(ServerRequest request) {
        return request.bodyToMono(ClinicalInsightRequest.class)
                .flatMap(healthInsightService::generateClinicalInsights)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> generateSummary(ServerRequest request) {
        return request.bodyToMono(SummaryRequest.class)
                .flatMap(healthInsightService::generateSummary)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> assessRisk(ServerRequest request) {
        return request.bodyToMono(RiskAssessmentRequest.class)
                .flatMap(healthInsightService::assessRisk)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> generateRecommendations(ServerRequest request) {
        return request.bodyToMono(RecommendationRequest.class)
                .flatMap(healthInsightService::generateRecommendations)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> analyzeTrends(ServerRequest request) {
        return request.bodyToMono(TrendAnalysisRequest.class)
                .flatMap(healthInsightService::analyzeTrends)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    public Mono<ServerResponse> generateEducationalContent(ServerRequest request) {
        return request.bodyToMono(EducationalContentRequest.class)
                .flatMap(healthInsightService::generateEducationalContent)
                .flatMap(this::ok)
                .onErrorResume(this::handleError);
    }

    private Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("LLM API error ({}): {}", llmClient.providerName(), error.getMessage());
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "llm",
                        "provider", llmClient.providerName()));
    }
}
