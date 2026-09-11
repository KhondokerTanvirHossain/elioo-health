package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.health.api.BedrockHealthService;
import com.elioo.healthcare.aws.bedrock.health.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.elioo.healthcare.aws.bedrock.health.dto.RiskAssessment;
import com.elioo.healthcare.aws.bedrock.health.dto.TrendAnalysisRequest;
import com.elioo.healthcare.aws.bedrock.health.dto.TrendAnalysisResponse;
import com.elioo.healthcare.aws.bedrock.health.dto.TrendPattern;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Adapter-level integration-style test using mocked BedrockHealthService.
 * Ensures mapping between domain and health DTOs without hitting AWS.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Clinical Insights Adapter Integration (with mocked health service)")
class BedrockAdapterIntegrationTest {

    @Mock
    private BedrockHealthService healthService;

    @Mock
    private BedrockService bedrockService;

    private ClinicalInsightPort insightPort;

    private static final Map<String, Object> SAMPLE_MEDICAL_DATA = Map.of(
            "HbA1c", "7.8%",
            "glucose", "125 mg/dL"
    );

    private static final ClinicalInsightPort.PatientContext SAMPLE_PATIENT_CONTEXT = new ClinicalInsightPort.PatientContext(
            "P001",
            55,
            "M",
            List.of("hypertension", "family history of diabetes"),
            List.of("metformin 500mg", "lisinopril 10mg"),
            List.of(),
            Map.of("weight", 85.0, "height", 175.0, "BMI", 27.8),
            Map.of("smoking", false, "exercise", "moderate", "diet", "balanced")
    );

    @BeforeEach
    void setUp() {
        insightPort = new BedrockAdapter(healthService, bedrockService, new ObjectMapper());
    }

    @Test
    @DisplayName("Generate patient-friendly summary")
    void testGenerateSummary_PatientAudience_HappyPath() {
        when(healthService.generateSummary(any(SummaryRequest.class)))
                .thenReturn(Mono.just(new SummaryResponse(
                        "Patient-friendly summary text about blood sugar and blood pressure.",
                        null,
                        List.of("Summary point 1")
                )));

        Mono<String> summaryMono = insightPort.generateSummary(SAMPLE_MEDICAL_DATA, "PATIENT");

        StepVerifier.create(summaryMono)
                .assertNext(summary -> {
                    assertThat(summary).isNotBlank();
                    assertThat(summary.toLowerCase()).contains("blood");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Assess patient risk based on medical data")
    void testAssessRisk_HappyPath() {
        RiskAssessmentResponse riskResponse = new RiskAssessmentResponse(
                new RiskAssessment(
                        Map.of("diabetes", new RiskScore("diabetes", "MODERATE", 0.6, "Pre-diabetic range")),
                        "MODERATE",
                        List.of("HbA1c 7.8%")
                ),
                List.of("Diet, exercise"),
                "Moderate risk"
        );

        when(healthService.assessRisk(any(com.elioo.healthcare.aws.bedrock.health.dto.RiskAssessmentRequest.class)))
                .thenReturn(Mono.just(riskResponse));

        ClinicalInsightPort.RiskAssessmentRequest request = new ClinicalInsightPort.RiskAssessmentRequest(
                SAMPLE_MEDICAL_DATA,
                SAMPLE_PATIENT_CONTEXT,
                List.of("diabetes", "cardiovascular", "kidney")
        );

        Mono<ClinicalInsightPort.RiskAssessment> riskMono = insightPort.assessRisk(request);

        StepVerifier.create(riskMono)
                .assertNext(risk -> {
                    assertThat(risk.overallRisk()).isEqualTo("MODERATE");
                    assertThat(risk.assessment()).isNotBlank();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Generate evidence-based recommendations")
    void testGenerateRecommendations_HappyPath() {
        RecommendationResponse recResponse = new RecommendationResponse(
                List.of(new ClinicalRecommendation(
                        "rec-1",
                        "LIFESTYLE",
                        "MEDIUM",
                        "Exercise 30 minutes daily",
                        "Improve glucose control",
                        "GUIDELINE_BASED",
                        "daily"
                )),
                "Summary"
        );

        when(healthService.generateRecommendations(any(RecommendationRequest.class)))
                .thenReturn(Mono.just(recResponse));

        List<KeyFinding> findings = List.of(
                new KeyFinding("HbA1c 7.8%", "MODERATE", "Pre-diabetes", "Needs lifestyle change", List.of("glucose"), Map.of())
        );

        Mono<List<Recommendation>> recommendationsMono = insightPort.generateRecommendations(findings, SAMPLE_PATIENT_CONTEXT);

        StepVerifier.create(recommendationsMono)
                .assertNext(recommendations -> {
                    assertThat(recommendations).isNotEmpty();
                    assertThat(recommendations.getFirst().recommendation()).contains("Exercise");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Analyze trends from historical data")
    void testAnalyzeTrends_HappyPath() {
        TrendAnalysisResponse trendResponse = new TrendAnalysisResponse(
                List.of(new TrendPattern("HbA1c", "INCREASING", "HbA1c levels trending upward, indicating worsening glucose control")),
                List.of(), // No anomalies
                Map.of(), // No predictions
                "HbA1c increasing over time from 7.2 to 7.6 mg/dL"
        );

        when(healthService.analyzeTrends(any(TrendAnalysisRequest.class)))
                .thenReturn(Mono.just(trendResponse));

        List<Map<String, Object>> historicalData = List.of(
                Map.of("timestamp", "2024-01-15T00:00:00Z", "metric", "HbA1c", "value", 7.2, "unit", "mg/dL"),
                Map.of("timestamp", "2024-04-15T00:00:00Z", "metric", "HbA1c", "value", 7.6, "unit", "mg/dL")
        );

        Mono<TrendAnalysis> trendMono = insightPort.analyzeTrends(historicalData, "HbA1c");

        StepVerifier.create(trendMono)
                .assertNext(trend -> {
                    assertThat(trend.metric()).isEqualTo("HbA1c");
                    assertThat(trend.direction()).isEqualTo("INCREASING");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Generate educational content for patients")
    void testGenerateEducationalContent_HappyPath() {
        EducationalContentResponse eduResponse = new EducationalContentResponse(
                "Pre-diabetes",
                "Content text",
                List.of("Point 1", "Point 2"),
                List.of(new FAQ("Point 1", "Point 2")),
                List.of("resource1")
        );

        when(healthService.generateEducationalContent(any(EducationalContentRequest.class)))
                .thenReturn(Mono.just(eduResponse));

        Mono<EducationalContent> contentMono = insightPort.generateEducationalContent(
                "Pre-diabetes",
                "SIMPLE"
        );

        StepVerifier.create(contentMono)
                .assertNext(content -> {
                    assertThat(content.topic()).contains("Pre-diabetes");
                    assertThat(content.keyPoints()).isNotEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Generate comprehensive clinical insights")
    void testGenerateClinicalInsights_CompleteWorkflow() {
        ClinicalInsightResponse response = new ClinicalInsightResponse(
                "Summary text",
                List.of(new ClinicalFinding("1", "HbA1c 7.8%", "LAB", "MODERATE", "Pre-diabetes", List.of("glucose"), Map.of())),
                List.of(new ClinicalRecommendation("rec1", "LIFESTYLE", "MEDIUM", "Exercise", "Improve glucose control", "GUIDELINE_BASED", "daily")),
                new RiskAssessment(Map.of("diabetes", new RiskScore("diabetes", "MODERATE", 0.6, "Pre-diabetes")), "MODERATE", List.of("HbA1c 7.8%")),
                null,
                0.9,
                Map.of()
        );

        when(healthService.generateClinicalInsights(any(ClinicalInsightRequest.class)))
                .thenReturn(Mono.just(response));

        InsightRequest request = new InsightRequest(
                "REPORT-001",
                SAMPLE_MEDICAL_DATA,
                Map.of("conditions", List.of("pre-diabetes", "hypertension")),
                SAMPLE_PATIENT_CONTEXT,
                new ClinicalInsightPort.SummaryOptions("PROVIDER", "en", true, "COMPREHENSIVE"),
                new ClinicalInsightPort.RiskAssessmentOptions(List.of("diabetes", "cardiovascular"), true, "5-year"),
                Map.of("urgency", "routine")
        );

        Mono<ClinicalInsightResult> insightMono = insightPort.generateClinicalInsights(request);

        StepVerifier.create(insightMono)
                .assertNext(result -> {
                    assertThat(result.reportId()).isEqualTo("REPORT-001");
                    assertThat(result.summary()).isNotBlank();
                    assertThat(result.riskLevel()).isNotNull();
                })
                .verifyComplete();
    }
}
