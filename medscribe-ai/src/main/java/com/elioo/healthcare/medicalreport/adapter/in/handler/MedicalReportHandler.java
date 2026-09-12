package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.domain.Priority;
import com.elioo.healthcare.medicalreport.domain.Severity;
import com.elioo.healthcare.medicalreport.domain.SuggestionCategory;
import com.elioo.healthcare.medicalreport.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportHandler {

    private final ClinicalInsightPort clinicalInsightPort;

    /**
     * Free-Text Insights Endpoint
     * Generates clinical insights from any free-form medical text
     */
    public Mono<ServerResponse> generateFreeTextInsights(ServerRequest request) {
        return request.bodyToMono(FreeTextInsightRequest.class)
                .flatMap(freeTextRequest -> {
                    log.info("Generating free-text insights, text length: {}",
                            freeTextRequest.getText().length());

                    return clinicalInsightPort.generateFreeTextInsights(
                            freeTextRequest.getText(),
                            freeTextRequest.getTargetAudience(),
                            freeTextRequest.getIncludeRiskAssessment(),
                            freeTextRequest.getIncludeRecommendations()
                    );
                })
                .flatMap(insightResult -> {
                    SuggestionsResponse response = mapToSuggestionsResponse(insightResult);
                    log.info("Free-text insights generated successfully, confidence: {}",
                            response.getConfidenceScore());
                    return ServerResponse.ok().bodyValue(response);
                })
                .onErrorResume(e -> {
                    log.error("Error generating free-text insights", e);
                    return ServerResponse.badRequest()
                            .bodyValue(new ErrorResponse(
                                    "Free-text insights generation failed: " + e.getMessage()));
                });
    }

    /**
     * Map FreeTextInsightResult to SuggestionsResponse.
     * Converts domain result to DTO response with appropriate formatting.
     */
    private SuggestionsResponse mapToSuggestionsResponse(
            ClinicalInsightPort.FreeTextInsightResult insightResult
    ) {
        // Convert key findings
        List<SuggestionsResponse.KeyFinding> keyFindings = insightResult.keyFindings().stream()
                .map(f -> SuggestionsResponse.KeyFinding.builder()
                        .finding(f.finding())
                        .severity(Severity.valueOf(f.severity()))
                        .interpretation(f.interpretation())
                        .normalRange(f.details().getOrDefault("normalRange", "").toString())
                        .build())
                .toList();

        // Convert AI suggestions
        List<SuggestionsResponse.AiSuggestion> aiSuggestions = insightResult.aiSuggestions().stream()
                .map(r -> SuggestionsResponse.AiSuggestion.builder()
                        .category(SuggestionCategory.valueOf(r.category()))
                        .priority(Priority.valueOf(r.priority()))
                        .recommendation(r.recommendation())
                        .rationale(r.rationale())
                        .build())
                .toList();

        // Generate unique report ID with "FT-" prefix to distinguish free-text reports
        String reportId = "FT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        return SuggestionsResponse.builder()
                .reportId(reportId)
                .summary(insightResult.summary())
                .keyFindings(keyFindings)
                .aiSuggestions(aiSuggestions)
                .insight("Analysis based on free-form medical text. " +
                        "Please consult a healthcare provider for clinical decisions and confirmation.")
                .riskLevel(Severity.valueOf(insightResult.riskLevel()))
                .requiresImmediateAttention(insightResult.requiresImmediateAttention())
                .generatedAt(LocalDateTime.now())
                .confidenceScore(insightResult.confidence())
                .build();
    }


        public record ErrorResponse(String message) {}
}
