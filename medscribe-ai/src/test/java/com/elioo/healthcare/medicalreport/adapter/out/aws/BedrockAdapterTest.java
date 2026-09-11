package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.bedrock.health.api.BedrockHealthService;
import com.elioo.healthcare.aws.bedrock.health.dto.*;
import com.elioo.healthcare.aws.bedrock.health.dto.RiskAssessment;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("Clinical Insights Unit Test - BedrockAdapter with Health Service Mocks")
class BedrockAdapterTest {

    @Mock
    private BedrockHealthService healthService;

    private BedrockAdapter bedrockAdapter;

    @BeforeEach
    void setUp() {
        bedrockAdapter = new BedrockAdapter(healthService);
    }

    @Test
    @DisplayName("Generate patient-friendly summary successfully")
    void testGenerateSummary_PatientAudience_Success() {
        when(healthService.generateSummary(any(SummaryRequest.class)))
                .thenReturn(Mono.just(new SummaryResponse(
                        "Your blood test shows mostly normal results.",
                        null,
                        List.of("Mostly normal", "Slightly elevated sugar")
                )));

        Map<String, Object> medicalData = Map.of(
                "HbA1c", "7.8%",
                "creatinine", "1.2 mg/dL"
        );

        Mono<String> summaryMono = bedrockAdapter.generateSummary(medicalData, "PATIENT");

        StepVerifier.create(summaryMono)
                .assertNext(summary -> {
                    assertThat(summary).isNotBlank();
                    assertThat(summary).contains("blood");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Generate clinical insights successfully")
    void testGenerateClinicalInsights_Success() {
        ClinicalInsightResponse response = new ClinicalInsightResponse(
                "Patient presents with elevated HbA1c indicating pre-diabetes",
                List.of(new ClinicalFinding("1", "HbA1c 7.8%", "LAB", "MODERATE",
                        "Pre-diabetes range", List.of("glucose"), Map.of())),
                List.of(new ClinicalRecommendation("rec1", "LIFESTYLE", "MEDIUM",
                        "Exercise", "Improve glucose control", "GUIDELINE_BASED", "daily")),
                new RiskAssessment(Map.of("diabetes", new RiskScore("diabetes", "MODERATE", 0.65,
                        "Pre-diabetic range")), "MODERATE", List.of("HbA1c 7.8%")),
                null,
                0.9,
                Map.of()
        );

        when(healthService.generateClinicalInsights(any(ClinicalInsightRequest.class)))
                .thenReturn(Mono.just(response));

        InsightRequest request = new InsightRequest(
                "REPORT-001",
                Map.of("testResults", List.of("HbA1c: 7.8%")),
                Map.of(),
                new ClinicalInsightPort.PatientContext("P001", 55, "M", List.of(), List.of(), List.of(), Map.of(), Map.of()),
                new ClinicalInsightPort.SummaryOptions("PROVIDER", "en", false, "MEDIUM"),
                new ClinicalInsightPort.RiskAssessmentOptions(List.of("diabetes"), true, "1-year"),
                Map.of()
        );

        Mono<ClinicalInsightResult> insightMono = bedrockAdapter.generateClinicalInsights(request);

        StepVerifier.create(insightMono)
                .assertNext(result -> {
                    assertThat(result.reportId()).isEqualTo("REPORT-001");
                    assertThat(result.summary()).isNotBlank();
                    assertThat(result.riskLevel()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Assess risk successfully")
    void testAssessRisk_Success() {
        RiskAssessmentResponse riskResponse = new RiskAssessmentResponse(
                new RiskAssessment(
                        Map.of("diabetes", new RiskScore("diabetes", "MODERATE", 0.65, "Pre-diabetic range")),
                        "MODERATE",
                        List.of("HbA1c 7.8%")
                ),
                List.of("Diet, exercise"),
                "Moderate risk"
        );

        when(healthService.assessRisk(any(com.elioo.healthcare.aws.bedrock.health.dto.RiskAssessmentRequest.class)))
                .thenReturn(Mono.just(riskResponse));

        ClinicalInsightPort.RiskAssessmentRequest request = new ClinicalInsightPort.RiskAssessmentRequest(
                Map.of("HbA1c", 7.8, "age", 55),
                new ClinicalInsightPort.PatientContext("P001", 55, "M", List.of("hypertension"), List.of("metformin"), List.of(), Map.of(), Map.of()),
                List.of("diabetes", "cardiovascular")
        );

        Mono<com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort.RiskAssessment> riskMono = bedrockAdapter.assessRisk(request);

        StepVerifier.create(riskMono)
                .assertNext(risk -> {
                    assertThat(risk.overallRisk()).isEqualTo("MODERATE");
                    assertThat(risk.riskFactors()).isNotNull();
                    assertThat(risk.assessment()).isNotBlank();
                })
                .verifyComplete();
    }
}
