package com.elioo.healthcare.llm.health.service;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.ClinicalInsightRequest;
import com.elioo.healthcare.llm.health.dto.ClinicalInsightResponse;
import com.elioo.healthcare.llm.health.dto.EducationalContentOptions;
import com.elioo.healthcare.llm.health.dto.EducationalContentRequest;
import com.elioo.healthcare.llm.health.dto.EducationalContentResponse;
import com.elioo.healthcare.llm.health.dto.InsightOptions;
import com.elioo.healthcare.llm.health.dto.RecommendationOptions;
import com.elioo.healthcare.llm.health.dto.RecommendationRequest;
import com.elioo.healthcare.llm.health.dto.RecommendationResponse;
import com.elioo.healthcare.llm.health.dto.RiskAssessmentOptions;
import com.elioo.healthcare.llm.health.dto.RiskAssessmentRequest;
import com.elioo.healthcare.llm.health.dto.RiskAssessmentResponse;
import com.elioo.healthcare.llm.health.dto.SummaryOptions;
import com.elioo.healthcare.llm.health.dto.SummaryRequest;
import com.elioo.healthcare.llm.health.dto.SummaryResponse;
import com.elioo.healthcare.llm.health.dto.TargetAudience;
import com.elioo.healthcare.llm.health.dto.TrendAnalysisOptions;
import com.elioo.healthcare.llm.health.dto.TrendAnalysisRequest;
import com.elioo.healthcare.llm.health.dto.TrendAnalysisResponse;
import com.elioo.healthcare.llm.health.exception.HealthInsightException;
import com.elioo.healthcare.llm.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.llm.json.LlmJsonExtractor;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Clinical prompt layer on top of any {@link LlmClient}: builds a prompt, sends it,
 * parses the JSON reply into a DTO. Stateless; no caching (the pipeline calls each
 * method once per report).
 */
@Slf4j
@RequiredArgsConstructor
public class HealthInsightServiceImpl implements HealthInsightService {

    private final LlmClient llmClient;
    private final PromptTemplateEngine promptEngine;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid clinical insight request: medical data is required"));
        }
        InsightOptions options = request.options() != null ? request.options() : InsightOptions.defaultPatient();
        String system = options.targetAudience() != null
                ? promptEngine.getSystemPrompt(options.targetAudience())
                : promptEngine.getSystemPrompt();
        return run("clinicalInsights",
                () -> promptEngine.buildClinicalInsightPrompt(request.medicalData(), request.patientContext(), options),
                system, ClinicalInsightResponse.class);
    }

    @Override
    public Mono<SummaryResponse> generateSummary(SummaryRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid summary request: medical data is required"));
        }
        SummaryOptions options = request.options() != null && request.options().targetAudience() != null
                ? request.options()
                : SummaryOptions.defaultPatient();
        return run("summary",
                () -> promptEngine.buildSummaryPrompt(request.medicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(options.targetAudience()), SummaryResponse.class);
    }

    @Override
    public Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid risk assessment request: medical data is required"));
        }
        RiskAssessmentOptions options = request.options() != null ? request.options() : RiskAssessmentOptions.defaultOptions();
        return run("riskAssessment",
                () -> promptEngine.buildRiskAssessmentPrompt(request.medicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), RiskAssessmentResponse.class);
    }

    @Override
    public Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid recommendation request: medical findings are required"));
        }
        RecommendationOptions options = request.options() != null ? request.options() : RecommendationOptions.defaultOptions();
        return run("recommendations",
                () -> promptEngine.buildRecommendationPrompt(request.medicalFindings(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), RecommendationResponse.class);
    }

    @Override
    public Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid trend analysis request: valid historical data is required"));
        }
        TrendAnalysisOptions options = request.options() != null ? request.options() : TrendAnalysisOptions.defaultOptions();
        return run("trendAnalysis",
                () -> promptEngine.buildTrendAnalysisPrompt(request.historicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), TrendAnalysisResponse.class);
    }

    @Override
    public Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid educational content request: topic is required"));
        }
        EducationalContentOptions options = request.options() != null ? request.options() : EducationalContentOptions.defaultPatient();
        return run("educationalContent",
                () -> promptEngine.buildEducationalContentPrompt(request.topic(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PATIENT), EducationalContentResponse.class);
    }

    @Override
    public <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new HealthInsightException("Prompt cannot be null or empty"));
        }
        return run("custom:" + responseClass.getSimpleName(), () -> prompt, null, responseClass);
    }

    private <T> Mono<T> run(String operation, Supplier<String> userPrompt, String systemPrompt, Class<T> type) {
        return Mono.fromCallable(() -> LlmRequest.forJson(userPrompt.get(), systemPrompt))
                .doOnNext(req -> log.info("[{}] sending {} chars to {}", operation,
                        req.userPrompt().length(), llmClient.providerName()))
                .flatMap(llmClient::invoke)
                .map(response -> parse(operation, response, type))
                .onErrorMap(e -> e instanceof HealthInsightException ? e
                        : new HealthInsightException("Health insight operation '" + operation + "' failed: " + e.getMessage(), e));
    }

    private <T> T parse(String operation, LlmResponse response, Class<T> type) {
        if (response == null || !response.hasContent()) {
            throw new HealthInsightException("[" + operation + "] empty response from " + llmClient.providerName());
        }
        try {
            String json = LlmJsonExtractor.extract(response.content());
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            String head = response.content().substring(0, Math.min(200, response.content().length()));
            log.warn("[{}] could not parse {} reply from {} ({}) as {}: {}", operation, response.modelId(),
                    llmClient.providerName(), e.getClass().getSimpleName(), type.getSimpleName(), head);
            log.debug("[{}] full unparseable content: {}", operation, response.content());
            throw new HealthInsightException("Failed to parse " + llmClient.providerName() + " reply into "
                    + type.getSimpleName() + ": " + head, e);
        }
    }
}
