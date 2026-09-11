package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.comprehendmedical.model.DetectedEntity;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityAttribute;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityDetectionResponse;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityTrait;
import com.elioo.healthcare.aws.comprehendmedical.model.MedicalCode;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort.ClassificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Medical Classification Unit Test - ComprehendMedicalAdapter with Service Mocks")
class ComprehendMedicalAdapterTest {

    @Mock
    private ComprehendMedicalService comprehendMedicalService;

    private ComprehendMedicalAdapter comprehendMedicalAdapter;

    private static final String SAMPLE_MEDICAL_TEXT =
            "Patient presents with hypertension and type 2 diabetes mellitus. " +
            "Currently taking metformin 500mg twice daily. " +
            "Blood pressure is 145/92 mmHg. HbA1c is 7.8%.";

    @BeforeEach
    void setUp() {
        comprehendMedicalAdapter = new ComprehendMedicalAdapter(comprehendMedicalService);
        // These are @Value-injected by Spring; outside a context they default to 0,
        // which makes every request "exceed maximum length". Mirror the property defaults.
        ReflectionTestUtils.setField(comprehendMedicalAdapter, "minConfidenceThreshold", 0.70);
        ReflectionTestUtils.setField(comprehendMedicalAdapter, "maxTextLength", 20000);
        ReflectionTestUtils.setField(comprehendMedicalAdapter, "maxCodesPerConcept", 3);
        ReflectionTestUtils.setField(comprehendMedicalAdapter, "maxEntities", 50);
    }

    @Test
    @DisplayName("Classify medical entities successfully")
    void testClassifyMedicalEntities_Success() {
        DetectedEntity hypertension = new DetectedEntity(
                1,
                "hypertension",
                "MEDICAL_CONDITION",
                "DX_NAME",
                0.95,
                20,
                32,
                List.of(),
                List.of(new EntityTrait("DIAGNOSIS", 0.9))
        );

        DetectedEntity metformin = new DetectedEntity(
                2,
                "metformin",
                "MEDICATION",
                "GENERIC_NAME",
                0.99,
                88,
                97,
                List.of(new EntityAttribute(
                        "DOSAGE",
                        0.97,
                        0.98,
                        3,
                        98,
                        104,
                        "500mg",
                        List.of()
                )),
                List.of()
        );

        EntityDetectionResponse response = new EntityDetectionResponse(
                List.of(hypertension, metformin),
                "1.0",
                Map.of()
        );

        when(comprehendMedicalService.detectEntities(any()))
                .thenReturn(Mono.just(response));

        ClassificationRequest request = new ClassificationRequest(
                SAMPLE_MEDICAL_TEXT,
                "en",
                List.of(),
                0.80,
                false,
                Map.of()
        );

        Mono<MedicalClassificationPort.ClassificationResult> resultMono =
                comprehendMedicalAdapter.classifyMedicalEntities(request);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.entities()).hasSize(2);
                    // Adapter orders entities by confidence (desc); assert membership, not position
                    assertThat(result.entities())
                            .extracting(MedicalClassificationPort.MedicalEntity::text)
                            .containsExactlyInAnyOrder("hypertension", "metformin");
                    assertThat(result.entities().get(0).text()).isEqualTo("metformin");
                    assertThat(result.overallConfidence()).isGreaterThan(0.9);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Map medical terms to ICD-10 and RxNorm codes")
    void testMapToMedicalCodes() {
        when(comprehendMedicalService.inferICD10Codes(any()))
                .thenReturn(Mono.just(List.of(new MedicalCode("I10", "Hypertension", "ICD-10-CM", 0.95))));
        when(comprehendMedicalService.inferRxNormCodes(any()))
                .thenReturn(Mono.just(List.of(new MedicalCode("860975", "Metformin", "RxNorm", 0.93))));

        Mono<List<MedicalClassificationPort.MedicalCode>> codesMono =
                comprehendMedicalAdapter.mapToMedicalCodes(
                        "hypertension and metformin",
                        List.of("ICD10", "RXNORM")
                );

        StepVerifier.create(codesMono)
                .assertNext(codes -> {
                    assertThat(codes).hasSize(2);
                    assertThat(codes.get(0).code()).isEqualTo("860975");
                    assertThat(codes.get(1).code()).isEqualTo("I10");
                })
                .verifyComplete();
    }
}
