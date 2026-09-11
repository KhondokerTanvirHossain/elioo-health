package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.domain.Priority;
import com.elioo.healthcare.medicalreport.domain.Severity;
import com.elioo.healthcare.medicalreport.domain.SuggestionCategory;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import com.elioo.healthcare.medicalreport.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportHandler {

    private final ClinicalInsightPort clinicalInsightPort;

    /**
     * OCR Processing Endpoint
     * Extracts structured medical test data from images
     */
    public Mono<ServerResponse> processOcr(ServerRequest request) {
        return request.bodyToMono(OcrRequest.class)
                .flatMap(ocrRequest -> {
                    log.info("Processing OCR for patient: {}", ocrRequest.getPatientId());

                    // TODO: Integrate with AWS Textract for actual OCR processing
                    // For now, returning mock data based on the example you provided

                    String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

                    List<TestResult> extractedData = createMockExtractedData();

                    OcrResponse response = OcrResponse.builder()
                            .reportId(reportId)
                            .patientId(ocrRequest.getPatientId())
                            .extractedData(extractedData)
                            .rawText("Serum Creatinine: 135.0 µmol/L...\nS. Electrolytes...\nSodium: 138.0 mmol/L...")
                            .confidence(0.96)
                            .processedAt(LocalDateTime.now())
                            .build();

                    log.info("OCR processing completed for report: {}", reportId);

                    return ServerResponse.ok().bodyValue(response);
                })
                .onErrorResume(e -> {
                    log.error("Error processing OCR", e);
                    return ServerResponse.badRequest()
                            .bodyValue(new ErrorResponse("OCR processing failed: " + e.getMessage()));
                });
    }

    /**
     * Medical Classification Endpoint
     * Classifies medical entities using AWS Comprehend Medical
     */
    public Mono<ServerResponse> classifyMedicalData(ServerRequest request) {
        return request.bodyToMono(ClassificationRequest.class)
                .flatMap(classificationRequest -> {
                    log.info("Classifying medical data for report: {}", classificationRequest.getReportId());

                    // TODO: Integrate with AWS Comprehend Medical
                    // For now, returning mock classification data

                    ClassificationResponse response = createMockClassificationResponse(
                            classificationRequest.getReportId()
                    );

                    log.info("Classification completed for report: {}", classificationRequest.getReportId());

                    return ServerResponse.ok().bodyValue(response);
                })
                .onErrorResume(e -> {
                    log.error("Error classifying medical data", e);
                    return ServerResponse.badRequest()
                            .bodyValue(new ErrorResponse("Classification failed: " + e.getMessage()));
                });
    }

    /**
     * AI Suggestions Endpoint
     * Generates AI-powered insights and recommendations
     */
    public Mono<ServerResponse> generateSuggestions(ServerRequest request) {
        return request.bodyToMono(SuggestionsRequest.class)
                .flatMap(suggestionsRequest -> {
                    log.info("Generating suggestions for report: {}", suggestionsRequest.getReportId());

                    // TODO: Integrate with AWS Bedrock (Claude 3) for AI generation
                    // For now, returning mock suggestions based on your example

                    SuggestionsResponse response = createMockSuggestionsResponse(
                            suggestionsRequest.getReportId()
                    );

                    log.info("Suggestions generated for report: {}", suggestionsRequest.getReportId());

                    return ServerResponse.ok().bodyValue(response);
                })
                .onErrorResume(e -> {
                    log.error("Error generating suggestions", e);
                    return ServerResponse.badRequest()
                            .bodyValue(new ErrorResponse("Suggestions generation failed: " + e.getMessage()));
                });
    }

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

    // Mock data creation methods

    private List<TestResult> createMockExtractedData() {
        List<TestResult> results = new ArrayList<>();

        results.add(TestResult.builder()
                .testName("Serum Creatinine")
                .testValue("135.0")
                .unit("µmol/L")
                .referenceRange("Male: 59-104, Female: 45-84")
                .status(TestStatus.ABNORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Sodium")
                .testValue("138.0")
                .unit("mmol/L")
                .referenceRange("136-148")
                .status(TestStatus.NORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Potassium")
                .testValue("3.5")
                .unit("mmol/L")
                .referenceRange("3.5-5.2")
                .status(TestStatus.NORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Chloride")
                .testValue("100.0")
                .unit("mmol/L")
                .referenceRange("98-108")
                .status(TestStatus.NORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Bilirubin")
                .testValue("82.0")
                .unit("µmol/L")
                .referenceRange("Adult upto: 19.0")
                .status(TestStatus.ABNORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("AST (SGOT)")
                .testValue("65.0")
                .unit("U/L")
                .referenceRange("Male: Upto 42, Female: Upto 32")
                .status(TestStatus.ABNORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("ALT (SGPT)")
                .testValue("28.0")
                .unit("U/L")
                .referenceRange("Male: Upto 42, Female: Upto 32")
                .status(TestStatus.NORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Albumin")
                .testValue("31.0")
                .unit("g/L")
                .referenceRange("35-57")
                .status(TestStatus.ABNORMAL)
                .build());

        results.add(TestResult.builder()
                .testName("Blood Ammonia")
                .testValue("244.0")
                .unit("µg/dL")
                .referenceRange("Adult: 19-54 µg/dL")
                .status(TestStatus.CRITICAL)
                .build());

        return results;
    }

    private ClassificationResponse createMockClassificationResponse(String reportId) {
        List<ClassificationResponse.MedicalEntity> entities = new ArrayList<>();

        // Entity 1: Serum Creatinine
        entities.add(ClassificationResponse.MedicalEntity.builder()
                .Id(1)
                .Text("Serum Creatinine")
                .Category("TEST_TREATMENT_PROCEDURE")
                .Type("TEST_NAME")
                .Score(0.98)
                .Attributes(Arrays.asList(
                        ClassificationResponse.EntityAttribute.builder()
                                .Type("TEST_VALUE")
                                .Score(0.99)
                                .RelationshipScore(0.97)
                                .RelationshipType("TEST_VALUE")
                                .Id(2)
                                .Text("135.0")
                                .Category("TEST_TREATMENT_PROCEDURE")
                                .Traits(new ArrayList<>())
                                .build()
                ))
                .build());

        // Entity 2: Blood Ammonia
        entities.add(ClassificationResponse.MedicalEntity.builder()
                .Id(3)
                .Text("Blood Ammonia")
                .Category("TEST_TREATMENT_PROCEDURE")
                .Type("TEST_NAME")
                .Score(0.97)
                .Attributes(Arrays.asList(
                        ClassificationResponse.EntityAttribute.builder()
                                .Type("TEST_VALUE")
                                .Score(0.98)
                                .RelationshipScore(0.96)
                                .RelationshipType("TEST_VALUE")
                                .Id(4)
                                .Text("244.0")
                                .Category("TEST_TREATMENT_PROCEDURE")
                                .Traits(new ArrayList<>())
                                .build()
                ))
                .build());

        ClassificationResponse.ComprehendMedicalResult comprehendResult =
                ClassificationResponse.ComprehendMedicalResult.builder()
                        .Entities(entities)
                        .build();

        ClassificationResponse.MedicalCodes medicalCodes =
                ClassificationResponse.MedicalCodes.builder()
                        .ICD10(Arrays.asList("R79.89", "N17.9"))
                        .LOINC(Arrays.asList("2160-0", "16362-6"))
                        .SNOMED(Arrays.asList("313822004", "43904001"))
                        .build();

        return ClassificationResponse.builder()
                .reportId(reportId)
                .classificationResult(comprehendResult)
                .medicalCodes(medicalCodes)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private SuggestionsResponse createMockSuggestionsResponse(String reportId) {
        List<SuggestionsResponse.KeyFinding> keyFindings = new ArrayList<>();

        keyFindings.add(SuggestionsResponse.KeyFinding.builder()
                .finding("Elevated Serum Creatinine (135.0 µmol/L)")
                .severity(Severity.MODERATE)
                .interpretation("Your kidney function shows mild impairment. Creatinine is a waste product that kidneys normally filter out.")
                .normalRange("Male: 59-104 µmol/L")
                .build());

        keyFindings.add(SuggestionsResponse.KeyFinding.builder()
                .finding("Significantly Elevated Blood Ammonia (244.0 µg/dL)")
                .severity(Severity.HIGH)
                .interpretation("This indicates your liver may not be processing waste products effectively. Elevated ammonia can affect brain function.")
                .normalRange("19-54 µg/dL")
                .build());

        keyFindings.add(SuggestionsResponse.KeyFinding.builder()
                .finding("Elevated Bilirubin (82.0 µmol/L)")
                .severity(Severity.HIGH)
                .interpretation("High bilirubin suggests liver dysfunction or bile flow issues. This can cause yellowing of skin and eyes (jaundice).")
                .normalRange("Up to 19.0 µmol/L")
                .build());

        keyFindings.add(SuggestionsResponse.KeyFinding.builder()
                .finding("Elevated AST (65.0 U/L)")
                .severity(Severity.MODERATE)
                .interpretation("This liver enzyme is elevated, suggesting liver cell damage or inflammation.")
                .normalRange("Male: Up to 42 U/L")
                .build());

        keyFindings.add(SuggestionsResponse.KeyFinding.builder()
                .finding("Low Albumin (31.0 g/L)")
                .severity(Severity.MODERATE)
                .interpretation("Low albumin indicates your liver may not be producing enough protein, which can affect fluid balance.")
                .normalRange("35-57 g/L")
                .build());

        List<SuggestionsResponse.AiSuggestion> aiSuggestions = Arrays.asList(
                SuggestionsResponse.AiSuggestion.builder()
                        .category(com.elioo.healthcare.medicalreport.domain.SuggestionCategory.IMMEDIATE_ACTION)
                        .priority(com.elioo.healthcare.medicalreport.domain.Priority.HIGH)
                        .recommendation("See a liver specialist (hepatologist) as soon as possible - within 24-48 hours")
                        .rationale("The combination of elevated ammonia, bilirubin, and liver enzymes with low albumin suggests significant liver dysfunction requiring immediate medical evaluation.")
                        .build(),

                SuggestionsResponse.AiSuggestion.builder()
                        .category(com.elioo.healthcare.medicalreport.domain.SuggestionCategory.DIAGNOSTIC_TESTS)
                        .priority(com.elioo.healthcare.medicalreport.domain.Priority.HIGH)
                        .recommendation("Additional tests needed: Complete liver panel, Hepatitis screening, Liver ultrasound or CT scan, Coagulation studies (PT/INR)")
                        .rationale("These tests will help determine the cause and extent of liver dysfunction.")
                        .build(),

                SuggestionsResponse.AiSuggestion.builder()
                        .category(com.elioo.healthcare.medicalreport.domain.SuggestionCategory.MEDICATION)
                        .priority(com.elioo.healthcare.medicalreport.domain.Priority.HIGH)
                        .recommendation("Your doctor may start you on lactulose or rifaximin to reduce blood ammonia levels")
                        .rationale("Elevated ammonia can lead to hepatic encephalopathy (confusion, altered mental state), which needs prompt treatment.")
                        .build(),

                SuggestionsResponse.AiSuggestion.builder()
                        .category(com.elioo.healthcare.medicalreport.domain.SuggestionCategory.MONITORING)
                        .priority(com.elioo.healthcare.medicalreport.domain.Priority.MEDIUM)
                        .recommendation("Close monitoring with repeat blood tests in 24-72 hours")
                        .rationale("Tracking trends in liver function and ammonia levels is crucial for treatment adjustment.")
                        .build(),

                SuggestionsResponse.AiSuggestion.builder()
                        .category(com.elioo.healthcare.medicalreport.domain.SuggestionCategory.LIFESTYLE)
                        .priority(com.elioo.healthcare.medicalreport.domain.Priority.MEDIUM)
                        .recommendation("Reduce protein intake temporarily, avoid alcohol completely, stay well hydrated")
                        .rationale("Reducing protein can help lower ammonia production; alcohol can worsen liver damage.")
                        .build()
        );

        return SuggestionsResponse.builder()
                .reportId(reportId)
                .summary("Your recent blood tests show some concerning results, particularly related to your liver function and body chemistry. The levels of certain substances in your blood suggest your liver may not be working as well as it should. This could be affecting how your body processes waste products and maintains its chemical balance.")
                .keyFindings(keyFindings)
                .aiSuggestions(aiSuggestions)
                .insight("These results indicate significant liver dysfunction that requires immediate medical attention. The good news is that many liver problems can be treated effectively if caught early and managed appropriately. It's crucial to follow up with your doctor or a liver specialist promptly to determine the underlying cause and start appropriate treatment.")
                .riskLevel(Severity.HIGH)
                .requiresImmediateAttention(true)
                .generatedAt(LocalDateTime.now())
                .confidenceScore(0.94)
                .build();
    }

    // Error response DTO
    public record ErrorResponse(String message) {}
}
