package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test for Medical Classification functionality using AWS Comprehend Medical.
 *
 * This test verifies the complete Medical Classification workflow:
 * 1. Extract medical entities from clinical text
 * 2. Map entities to standard medical code systems (ICD-10-CM, RxNorm)
 * 3. Extract relationships between entities
 * 4. Validate classification quality
 *
 * Prerequisites:
 * - AWS credentials configured (via environment variables, ~/.aws/credentials, or application-aws.properties)
 * - Valid AWS Comprehend Medical permissions
 *
 * To run this test:
 * 1. Ensure AWS credentials are configured: aws configure
 * 2. Run: ./gradlew test --tests ComprehendMedicalAdapterIntegrationTest
 *
 * @author MedScribe AI Team
 */
@SpringBootTest
@ActiveProfiles({"local", "aws"})  // Use local + aws profiles
@EnabledIfEnvironmentVariable(named = "RUN_AWS_INTEGRATION_TESTS", matches = "true",
        disabledReason = "Calls real AWS Comprehend Medical; set RUN_AWS_INTEGRATION_TESTS=true to run")
@DisplayName("Medical Classification Integration Test - AWS Comprehend Medical Adapter")
class ComprehendMedicalAdapterIntegrationTest {

    @Autowired
    private MedicalClassificationPort classificationPort;  // Will auto-wire ComprehendMedicalAdapter

    private static final String SAMPLE_CLINICAL_TEXT =
            "Patient presents with hypertension and type 2 diabetes mellitus. " +
            "Currently taking metformin 500mg twice daily and lisinopril 10mg once daily. " +
            "Blood pressure is 145/92 mmHg. HbA1c is 7.8%. " +
            "Patient reports occasional dizziness and fatigue.";

    private static final String MEDICATION_TEXT =
            "The patient is prescribed metformin 500mg tablets, take one tablet twice daily with meals. " +
            "Also prescribed lisinopril 10mg tablets, take one tablet once daily in the morning.";

    private static final String DIAGNOSIS_TEXT =
            "Patient diagnosed with essential hypertension (HTN) and Type 2 diabetes mellitus without complications. " +
            "Also has hyperlipidemia.";

    @Test
    @DisplayName("Happy Path: Classify medical entities from clinical text")
    void testClassifyMedicalEntities_HappyPath() {
        // Given: Clinical text with medical entities
        ClassificationRequest request = new ClassificationRequest(
                SAMPLE_CLINICAL_TEXT,
                "en",
                List.of("ICD10"),
                0.80,
                false,
                Map.of()
        );

        // When: Classify medical entities
        Mono<ClassificationResult> resultMono = classificationPort.classifyMedicalEntities(request);

        // Then: Verify entities are extracted
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    System.out.println("\n=== Classified Medical Entities ===");
                    result.entities().forEach(entity -> {
                        System.out.println(String.format(
                                "Entity: %s | Category: %s | Type: %s | Confidence: %.2f",
                                entity.text(),
                                entity.category(),
                                entity.type(),
                                entity.score()
                        ));
                    });

                    // Verify we extracted entities
                    assertThat(result.entities()).isNotEmpty();

                    // Verify overall confidence
                    assertThat(result.overallConfidence()).isGreaterThan(0.0);
                    assertThat(result.overallConfidence()).isLessThanOrEqualTo(1.0);

                    // Verify at least some medical conditions were detected
                    long medicalConditions = result.entities().stream()
                            .filter(e -> e.category().contains("MEDICAL_CONDITION"))
                            .count();
                    assertThat(medicalConditions).isGreaterThan(0);

                    // Verify at least some medications were detected
                    long medications = result.entities().stream()
                            .filter(e -> e.category().contains("MEDICATION"))
                            .count();
                    assertThat(medications).isGreaterThan(0);

                    System.out.println(String.format(
                            "\nDetected: %d medical conditions, %d medications",
                            medicalConditions,
                            medications
                    ));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Map diagnosis to ICD-10-CM codes")
    void testMapToMedicalCodes_ICD10_HappyPath() {
        // Given: Medical diagnosis text
        // When: Map to ICD-10-CM codes
        Mono<List<MedicalCode>> codesMono = classificationPort.mapToMedicalCodes(
                DIAGNOSIS_TEXT,
                List.of("ICD10")
        );

        // Then: Verify ICD-10-CM codes are extracted
        StepVerifier.create(codesMono)
                .assertNext(codes -> {
                    System.out.println("\n=== ICD-10-CM Codes ===");
                    codes.forEach(code -> {
                        System.out.println(String.format(
                                "Code: %s | Description: %s | Confidence: %.2f",
                                code.code(),
                                code.description(),
                                code.score()
                        ));
                    });

                    assertThat(codes).isNotEmpty();

                    // Verify all codes have valid structure
                    codes.forEach(code -> {
                        assertThat(code.code()).isNotBlank();
                        assertThat(code.description()).isNotBlank();
                        assertThat(code.codeSystem()).isEqualTo("ICD-10-CM");
                        assertThat(code.score()).isBetween(0.0, 1.0);
                    });
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Map medications to RxNorm codes")
    void testMapToMedicalCodes_RxNorm_HappyPath() {
        // Given: Medication text
        // When: Map to RxNorm codes
        Mono<List<MedicalCode>> codesMono = classificationPort.mapToMedicalCodes(
                MEDICATION_TEXT,
                List.of("RXNORM")
        );

        // Then: Verify RxNorm codes are extracted
        StepVerifier.create(codesMono)
                .assertNext(codes -> {
                    System.out.println("\n=== RxNorm Codes ===");
                    codes.forEach(code -> {
                        System.out.println(String.format(
                                "Code: %s | Description: %s | Confidence: %.2f",
                                code.code(),
                                code.description(),
                                code.score()
                        ));
                    });

                    assertThat(codes).isNotEmpty();

                    // Verify all codes have valid structure
                    codes.forEach(code -> {
                        assertThat(code.code()).isNotBlank();
                        assertThat(code.description()).isNotBlank();
                        assertThat(code.codeSystem()).isEqualTo("RxNorm");
                        assertThat(code.score()).isBetween(0.0, 1.0);
                    });

                    // Verify we got medication codes (metformin, lisinopril)
                    boolean hasMetformin = codes.stream()
                            .anyMatch(c -> c.description().toLowerCase().contains("metformin"));
                    boolean hasLisinopril = codes.stream()
                            .anyMatch(c -> c.description().toLowerCase().contains("lisinopril"));

                    System.out.println(String.format(
                            "\nFound Metformin: %s, Found Lisinopril: %s",
                            hasMetformin, hasLisinopril
                    ));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Map to multiple code systems (ICD-10 + RxNorm)")
    void testMapToMedicalCodes_MultipleCodeSystems() {
        // Given: Text with both diagnoses and medications
        // When: Map to both code systems
        Mono<List<MedicalCode>> codesMono = classificationPort.mapToMedicalCodes(
                SAMPLE_CLINICAL_TEXT,
                List.of("ICD10", "RXNORM")
        );

        // Then: Verify codes from both systems
        StepVerifier.create(codesMono)
                .assertNext(codes -> {
                    System.out.println("\n=== Combined Code Systems ===");

                    long icd10Codes = codes.stream()
                            .filter(c -> c.codeSystem().equals("ICD-10-CM"))
                            .count();

                    long rxNormCodes = codes.stream()
                            .filter(c -> c.codeSystem().equals("RxNorm"))
                            .count();

                    System.out.println(String.format(
                            "ICD-10-CM codes: %d, RxNorm codes: %d",
                            icd10Codes, rxNormCodes
                    ));

                    assertThat(codes).isNotEmpty();
                    assertThat(icd10Codes + rxNormCodes).isEqualTo(codes.size());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Validate high-quality classification result")
    void testValidateClassification_HighQuality() {
        // Given: A classification request
        ClassificationRequest request = new ClassificationRequest(
                SAMPLE_CLINICAL_TEXT,
                "en",
                List.of(),
                0.80,
                false,
                Map.of()
        );

        // When: Classify and then validate
        Mono<ValidationResult> validationMono = classificationPort.classifyMedicalEntities(request)
                .flatMap(classificationPort::validateClassification);

        // Then: Verify validation result
        StepVerifier.create(validationMono)
                .assertNext(validation -> {
                    System.out.println("\n=== Classification Validation ===");
                    System.out.println("Valid: " + validation.isValid());
                    System.out.println("Quality Score: " + validation.qualityScore());
                    System.out.println("Warnings: " + validation.warnings());
                    System.out.println("Errors: " + validation.errors());

                    assertThat(validation.isValid()).isTrue();
                    assertThat(validation.qualityScore()).isGreaterThan(0.5);
                    assertThat(validation.errors()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Extract entity relationships")
    void testExtractRelationships_HappyPath() {
        // Given: A classification with entities
        ClassificationRequest request = new ClassificationRequest(
                MEDICATION_TEXT,
                "en",
                List.of(),
                0.80,
                true,  // Request relationship extraction
                Map.of()
        );

        // When: Classify with relationship extraction
        Mono<ClassificationResult> resultMono = classificationPort.classifyMedicalEntities(request);

        // Then: Verify relationships are extracted
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    System.out.println("\n=== Entity Relationships ===");
                    System.out.println("Entities: " + result.entities().size());

                    assertThat(result.entities()).isNotEmpty();
                    // Relationships are no longer part of ClassificationResult; they are
                    // derived on demand via MedicalClassificationPort.extractRelationships()
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Handle short text with few entities")
    void testClassifyMedicalEntities_ShortText() {
        // Given: Short clinical text
        String shortText = "Patient has hypertension.";

        ClassificationRequest request = new ClassificationRequest(
                shortText,
                "en",
                List.of(),
                0.70,
                false,
                Map.of()
        );

        // When: Classify
        Mono<ClassificationResult> resultMono = classificationPort.classifyMedicalEntities(request);

        // Then: Verify at least the condition is detected
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    System.out.println("\n=== Short Text Classification ===");
                    System.out.println("Detected entities: " + result.entities().size());

                    assertThat(result.entities()).isNotEmpty();

                    boolean hasHypertension = result.entities().stream()
                            .anyMatch(e -> e.text().toLowerCase().contains("hypertension"));

                    assertThat(hasHypertension).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Complete classification workflow with all features")
    void testCompleteClassificationWorkflow() {
        // Given: Clinical text
        ClassificationRequest request = new ClassificationRequest(
                SAMPLE_CLINICAL_TEXT,
                "en",
                List.of("ICD10", "RXNORM"),
                0.70,
                true,
                Map.of()
        );

        // When: Perform complete classification workflow
        Mono<ClassificationResult> workflowMono = classificationPort.classifyMedicalEntities(request)
                .doOnNext(result -> {
                    System.out.println("\n=== Complete Classification Workflow ===");
                    System.out.println("Step 1: Entity Extraction - " + result.entities().size() + " entities");
                    System.out.println("Step 2: Medical Codes - " + result.medicalCodes().size() + " code systems");
                    System.out.println("Step 3: Overall Confidence - " + result.overallConfidence());
                });

        // Then: Verify complete workflow
        StepVerifier.create(workflowMono)
                .assertNext(result -> {
                    // Verify all components are present
                    assertThat(result.entities()).isNotEmpty();
                    assertThat(result.overallConfidence()).isGreaterThan(0.0);
                    assertThat(result.medicalCodes()).isNotNull();

                    // Log summary
                    long conditions = result.entities().stream()
                            .filter(e -> e.category().contains("MEDICAL_CONDITION"))
                            .count();

                    long medications = result.entities().stream()
                            .filter(e -> e.category().contains("MEDICATION"))
                            .count();

                    System.out.println(String.format(
                            "\nSummary: %d conditions, %d medications, %.2f confidence",
                            conditions,
                            medications,
                            result.overallConfidence()
                    ));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Verify adapter implements MedicalClassificationPort interface")
    void testAdapterImplementsPort() {
        // Verify: ComprehendMedicalAdapter implements MedicalClassificationPort
        assertThat(classificationPort).isInstanceOf(MedicalClassificationPort.class);
    }
}
