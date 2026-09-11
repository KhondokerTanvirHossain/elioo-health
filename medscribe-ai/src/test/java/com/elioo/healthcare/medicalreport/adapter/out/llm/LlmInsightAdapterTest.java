package com.elioo.healthcare.medicalreport.adapter.out.llm;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.elioo.healthcare.llm.health.dto.RiskAssessment;
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
@DisplayName("Clinical Insights Unit Test - LlmInsightAdapter with Health Service Mocks")
class LlmInsightAdapterTest {

    @Mock
    private HealthInsightService healthService;

    @Mock
    private LlmClient llmClient;

    private LlmInsightAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LlmInsightAdapter(healthService, llmClient, new ObjectMapper());
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

        Mono<String> summaryMono = adapter.generateSummary(medicalData, "PATIENT");

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

        Mono<ClinicalInsightResult> insightMono = adapter.generateClinicalInsights(request);

        StepVerifier.create(insightMono)
                .assertNext(result -> {
                    assertThat(result.reportId()).isEqualTo("REPORT-001");
                    assertThat(result.summary()).isNotBlank();
                    assertThat(result.riskLevel()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Chat prompt embeds a compact report digest, not raw JSON")
    void testChatPromptIsCompact() {
        when(llmClient.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("The RDW value is the one to discuss.")));

        // a realistic context: many test rows, a long raw OCR text, and full classification output
        StringBuilder rawText = new StringBuilder();
        for (int i = 0; i < 400; i++) rawText.append("line ").append(i).append(" of OCR noise that must not reach the prompt\n");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(Map.of("testName", "Test" + i, "testValue", "1." + i, "unit", "g/dL", "referenceRange", "1-2", "status", "NORMAL"));
        }
        Map<String, Object> context = Map.of(
                "ocrResults", Map.of("extractedData", rows, "rawText", rawText.toString()),
                "classificationResults", Map.of("classificationResult", Map.of("entities", rows)),
                "icd10Codes", List.of(Map.of("code", "D75.9", "description", "Disease of blood", "score", 0.4)),
                "clinicalInsights", Map.of("summary", "Mostly normal CBC.", "riskLevel", "LOW",
                        "keyFindings", List.of(Map.of("finding", "RDW 16.7%", "severity", "MODERATE", "interpretation", "Variation in red cell size"))));

        StepVerifier.create(adapter.chatAboutReport("RPT-1", "Which value is most concerning?", List.of(), context, null))
                .assertNext(answer -> assertThat(answer).contains("RDW"))
                .verifyComplete();

        org.mockito.ArgumentCaptor<LlmRequest> captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmClient).invoke(captor.capture());
        String prompt = captor.getValue().userPrompt();
        assertThat(prompt).doesNotContain("OCR noise");
        assertThat(prompt).doesNotContain("entities");
        assertThat(prompt).contains("Test0: 1.0 g/dL").contains("D75.9").contains("RDW 16.7%").contains("Mostly normal CBC.");
        assertThat(prompt.length()).isLessThan(LlmInsightAdapter.CHAT_CONTEXT_MAX_CHARS + 3000);
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

        when(healthService.assessRisk(any(com.elioo.healthcare.llm.health.dto.RiskAssessmentRequest.class)))
                .thenReturn(Mono.just(riskResponse));

        ClinicalInsightPort.RiskAssessmentRequest request = new ClinicalInsightPort.RiskAssessmentRequest(
                Map.of("HbA1c", 7.8, "age", 55),
                new ClinicalInsightPort.PatientContext("P001", 55, "M", List.of("hypertension"), List.of("metformin"), List.of(), Map.of(), Map.of()),
                List.of("diabetes", "cardiovascular")
        );

        Mono<com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort.RiskAssessment> riskMono = adapter.assessRisk(request);

        StepVerifier.create(riskMono)
                .assertNext(risk -> {
                    assertThat(risk.overallRisk()).isEqualTo("MODERATE");
                    assertThat(risk.riskFactors()).isNotNull();
                    assertThat(risk.assessment()).isNotBlank();
                })
                .verifyComplete();
    }
}
