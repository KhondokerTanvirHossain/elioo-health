package com.elioo.healthcare.aws.bedrock.health.service;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.health.api.BedrockHealthService;
import com.elioo.healthcare.aws.bedrock.health.dto.*;
import com.elioo.healthcare.aws.bedrock.health.exception.BedrockHealthServiceException;
import com.elioo.healthcare.aws.bedrock.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.aws.bedrock.model.LlmRequest;
import com.elioo.healthcare.aws.bedrock.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default implementation of BedrockHealthService using AWS Bedrock.
 *
 * <p>This implementation:</p>
 * <ul>
 *   <li>Uses {@link BedrockService} for low-level model invocation</li>
 *   <li>Uses {@link PromptTemplateEngine} for building structured prompts</li>
 *   <li>Parses JSON responses into strongly-typed DTOs</li>
 *   <li>Provides caching for cost optimization (using Spring Cache)</li>
 *   <li>Includes comprehensive error handling and logging</li>
 * </ul>
 *
 * <p><b>Caching Strategy:</b></p>
 * <p>Results are cached based on request content to avoid duplicate AI calls
 * for identical requests. This significantly reduces costs and latency for
 * repeated operations. Cache TTL and eviction policy are configured via
 * Spring Cache configuration.</p>
 *
 * @since 0.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BedrockHealthServiceImpl implements BedrockHealthService {

    private final BedrockService bedrockService;
    private final PromptTemplateEngine promptEngine;
    private final ObjectMapper objectMapper;

    @Override
    @Cacheable(value = "clinicalInsights", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request) {
        log.info("Generating clinical insights for request: {}", request);

        // Validate request
        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid clinical insight request: medical data is required"
            ));
        }

        return Mono.fromCallable(() -> {
                    // Build prompt using template engine
                    String systemPrompt = request.options() != null && request.options().targetAudience() != null ?
                            promptEngine.getSystemPrompt(request.options().targetAudience()) :
                            promptEngine.getSystemPrompt();

                    String userPrompt = promptEngine.buildClinicalInsightPrompt(
                            request.medicalData(),
                            request.patientContext(),
                            request.options() != null ? request.options() : InsightOptions.defaultPatient()
                    );

                    log.info("Built clinical insight prompt: {} chars", userPrompt.length());

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, ClinicalInsightResponse.class))
                .doOnSuccess(response -> log.info(
                        "Generated clinical insights with {} findings and {} recommendations",
                        response.hasFindings() ? response.keyFindings().size() : 0,
                        response.hasRecommendations() ? response.recommendations().size() : 0
                ))
                .doOnError(error -> log.error("Error generating clinical insights", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    @Cacheable(value = "summaries", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<SummaryResponse> generateSummary(SummaryRequest request) {
        log.info("Generating summary for request: {}", request);

        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid summary request: medical data is required"
            ));
        }

        // Normalize options to ensure audience is set
        SummaryOptions options = request.options() != null && request.options().targetAudience() != null
                ? request.options()
                : SummaryOptions.defaultPatient();

        return Mono.fromCallable(() -> {
                    String systemPrompt = promptEngine.getSystemPrompt(options.targetAudience());

                    String userPrompt = promptEngine.buildSummaryPrompt(
                            request.medicalData(),
                            request.patientContext(),
                            options
                    );

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, SummaryResponse.class))
                .doOnSuccess(response -> log.info("Generated summary: {} chars", response.getSummaryLength()))
                .doOnError(error -> log.error("Error generating summary", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    @Cacheable(value = "riskAssessments", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request) {
        log.info("Assessing risk for request: {}", request);

        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid risk assessment request: medical data is required"
            ));
        }

        return Mono.fromCallable(() -> {
                    String systemPrompt = promptEngine.getSystemPrompt(TargetAudience.PROVIDER);

                    String userPrompt = promptEngine.buildRiskAssessmentPrompt(
                            request.medicalData(),
                            request.patientContext(),
                            request.options() != null ? request.options() : RiskAssessmentOptions.defaultOptions()
                    );

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, RiskAssessmentResponse.class))
                .doOnSuccess(response -> log.info(
                        "Generated risk assessment: overall risk = {}",
                        response.riskAssessment() != null ? response.riskAssessment().overallRiskLevel() : "N/A"
                ))
                .doOnError(error -> log.error("Error assessing risk", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    @Cacheable(value = "recommendations", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request) {
        log.info("Generating recommendations for request: {}", request);

        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid recommendation request: medical findings are required"
            ));
        }

        return Mono.fromCallable(() -> {
                    String systemPrompt = promptEngine.getSystemPrompt(TargetAudience.PROVIDER);

                    String userPrompt = promptEngine.buildRecommendationPrompt(
                            request.medicalFindings(),
                            request.patientContext(),
                            request.options() != null ? request.options() : RecommendationOptions.defaultOptions()
                    );

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, RecommendationResponse.class))
                .doOnSuccess(response -> log.info(
                        "Generated {} recommendations ({} urgent)",
                        response.getRecommendationCount(),
                        response.getUrgentCount()
                ))
                .doOnError(error -> log.error("Error generating recommendations", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    @Cacheable(value = "trendAnalysis", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request) {
        log.info("Analyzing trends for request: {} data points", request.getDataPointCount());

        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid trend analysis request: valid historical data is required"
            ));
        }

        return Mono.fromCallable(() -> {
                    String systemPrompt = promptEngine.getSystemPrompt(TargetAudience.PROVIDER);

                    String userPrompt = promptEngine.buildTrendAnalysisPrompt(
                            request.historicalData(),
                            request.patientContext(),
                            request.options() != null ? request.options() : TrendAnalysisOptions.defaultOptions()
                    );

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, TrendAnalysisResponse.class))
                .doOnSuccess(response -> log.info(
                        "Analyzed trends: {} patterns, {} anomalies, {} predictions",
                        response.hasPatterns() ? response.patterns().size() : 0,
                        response.hasAnomalies() ? response.anomalies().size() : 0,
                        response.hasPredictions() ? response.predictions().size() : 0
                ))
                .doOnError(error -> log.error("Error analyzing trends", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    @Cacheable(value = "educationalContent", key = "T(java.util.Objects).hash(#request)", unless = "#result == null or #request == null")
    public Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request) {
        log.info("Generating educational content for topic: {}", request.topic());

        if (request == null || !request.isValid()) {
            return Mono.error(new BedrockHealthServiceException(
                    "Invalid educational content request: topic is required"
            ));
        }

        return Mono.fromCallable(() -> {
                    String systemPrompt = promptEngine.getSystemPrompt(TargetAudience.PATIENT);

                    String userPrompt = promptEngine.buildEducationalContentPrompt(
                            request.topic(),
                            request.patientContext(),
                            request.options() != null ? request.options() : EducationalContentOptions.defaultPatient()
                    );

                    return LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
                })
                .flatMap(bedrockService::invokeModel)
                .map(response -> parseResponse(response, EducationalContentResponse.class))
                .doOnSuccess(response -> log.info(
                        "Generated educational content: {} chars, {} FAQs",
                        response.getContentLength(),
                        response.hasFaqs() ? response.faqs().size() : 0
                ))
                .doOnError(error -> log.error("Error generating educational content", error))
                .onErrorMap(this::wrapException);
    }

    @Override
    public <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass) {
        log.info("Executing custom prompt for response class: {}", responseClass.getSimpleName());

        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new BedrockHealthServiceException("Prompt cannot be null or empty"));
        }

        return bedrockService.invokeClaude(prompt, null)
                .map(response -> parseResponse(response, responseClass))
                .doOnSuccess(response -> log.info("Executed custom prompt successfully"))
                .doOnError(error -> log.error("Error executing custom prompt", error))
                .onErrorMap(this::wrapException);
    }

    // Helper methods

    /**
     * Parse LLM response into typed DTO.
     *
     * <p>This method extracts JSON from the response content and deserializes
     * it into the specified class. It handles cases where the JSON might be
     * wrapped in markdown code blocks.</p>
     */
    private <T> T parseResponse(LlmResponse llmResponse, Class<T> responseClass) {
        if (llmResponse == null || !llmResponse.hasContent()) {
            throw new BedrockHealthServiceException("Empty response from LLM");
        }

        String content = llmResponse.content();

        // Remove markdown code blocks if present
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        try {
            T result = objectMapper.readValue(content, responseClass);
            log.debug("Successfully parsed response into {}", responseClass.getSimpleName());
            return result;
        } catch (Exception e) {
            log.error("Failed to parse LLM response into {}: {}", responseClass.getSimpleName(), content);
            throw new BedrockHealthServiceException(
                    "Failed to parse LLM response into " + responseClass.getSimpleName() +
                            ". Response content: " + content.substring(0, Math.min(200, content.length())),
                    e
            );
        }
    }

    /**
     * Wrap exceptions in BedrockHealthServiceException if needed.
     */
    private Throwable wrapException(Throwable throwable) {
        if (throwable instanceof BedrockHealthServiceException) {
            return throwable;
        }
        return new BedrockHealthServiceException("Health service operation failed", throwable);
    }
}
