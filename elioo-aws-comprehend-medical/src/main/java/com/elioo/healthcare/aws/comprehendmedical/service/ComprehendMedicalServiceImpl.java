package com.elioo.healthcare.aws.comprehendmedical.service;

import com.elioo.healthcare.aws.common.exception.AwsValidationException;
import com.elioo.healthcare.aws.comprehendmedical.config.ComprehendMedicalProperties;
import com.elioo.healthcare.aws.comprehendmedical.model.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.comprehendmedical.ComprehendMedicalAsyncClient;
import software.amazon.awssdk.services.comprehendmedical.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS Comprehend Medical implementation of medical NLP service.
 *
 * <p>This implementation uses Amazon Comprehend Medical for medical text analysis.
 * It provides domain-agnostic medical entity detection and classification:</p>
 * <ul>
 *   <li>Entity detection (medications, conditions, anatomy, tests, PHI)</li>
 *   <li>Medical code inference (ICD-10-CM, RxNorm)</li>
 *   <li>Text validation</li>
 * </ul>
 *
 * <p>Domain-specific interpretation should be implemented in the consuming application.</p>
 *
 * @see com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService
 * @since 0.1.0
 */
@Slf4j
public class ComprehendMedicalServiceImpl implements com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService {

    private final ComprehendMedicalAsyncClient comprehendMedicalClient;
    private final ComprehendMedicalProperties properties;

    public ComprehendMedicalServiceImpl(
            ComprehendMedicalAsyncClient comprehendMedicalClient,
            ComprehendMedicalProperties properties
    ) {
        this.comprehendMedicalClient = comprehendMedicalClient;
        this.properties = properties;
    }

    /**
     * Detects medical entities in text.
     */
    public Mono<EntityDetectionResponse> detectEntities(EntityDetectionRequest request) {
        log.info("Detecting medical entities in text (length: {})", request.text().length());

        return validateText(request.text())
                .then(Mono.fromFuture(() -> {
                    DetectEntitiesV2Request awsRequest = DetectEntitiesV2Request.builder()
                            .text(request.text())
                            .build();
                    return comprehendMedicalClient.detectEntitiesV2(awsRequest);
                }))
                .map(this::mapToEntityDetectionResponse)
                .doOnSuccess(response -> log.info("Detected {} entities", response.getEntityCount()))
                .doOnError(error -> log.error("Entity detection failed", error));
    }

    /**
     * Infers ICD-10-CM codes from medical text.
     */
    public Mono<List<MedicalCode>> inferICD10Codes(String text) {
        log.info("Inferring ICD-10-CM codes from text");

        return validateText(text)
                .then(Mono.fromFuture(() -> {
                    InferIcd10CmRequest request = InferIcd10CmRequest.builder()
                            .text(text)
                            .build();
                    return comprehendMedicalClient.inferICD10CM(request);
                }))
                .map(response -> response.entities().stream()
                        .flatMap(entity -> entity.icd10CMConcepts().stream()
                                .map(concept -> new MedicalCode(
                                        concept.code(),
                                        concept.description(),
                                        "ICD-10-CM",
                                        concept.score() != null ? concept.score().doubleValue() : 0.0
                                ))
                        )
                        .toList()
                );
    }

    /**
     * Infers RxNorm codes from medication text.
     */
    public Mono<List<MedicalCode>> inferRxNormCodes(String text) {
        log.info("Inferring RxNorm codes from text");

        return validateText(text)
                .then(Mono.fromFuture(() -> {
                    InferRxNormRequest request = InferRxNormRequest.builder()
                            .text(text)
                            .build();
                    return comprehendMedicalClient.inferRxNorm(request);
                }))
                .map(response -> response.entities().stream()
                        .flatMap(entity -> entity.rxNormConcepts().stream()
                                .map(concept -> new MedicalCode(
                                        concept.code(),
                                        concept.description(),
                                        "RxNorm",
                                        concept.score() != null ? concept.score().doubleValue() : 0.0
                                ))
                        )
                        .toList()
                );
    }

    /**
     * Infers SNOMED CT codes from medical text.
     */
    @Override
    public Mono<List<MedicalCode>> inferSNOMEDCTCodes(String text) {
        log.info("Inferring SNOMED CT codes from text");

        return validateText(text)
                .then(Mono.fromFuture(() -> {
                    InferSnomedctRequest request = InferSnomedctRequest.builder()
                            .text(text)
                            .build();
                    return comprehendMedicalClient.inferSNOMEDCT(request);
                }))
                .map(response -> response.entities().stream()
                        .flatMap(entity -> entity.snomedctConcepts().stream()
                                .map(concept -> new MedicalCode(
                                        concept.code(),
                                        concept.description(),
                                        "SNOMED-CT",
                                        concept.score() != null ? concept.score().doubleValue() : 0.0
                                ))
                        )
                        .toList()
                );
    }

    /**
     * Validates text before processing.
     */
    private Mono<Void> validateText(String text) {
        return Mono.fromRunnable(() -> {
            if (text == null || text.isBlank()) {
                throw new AwsValidationException("text", text, "Text cannot be null or blank");
            }
            if (text.length() > properties.getMaxTextLength()) {
                throw new AwsValidationException(
                        "text",
                        text.length(),
                        String.format("Text length %d exceeds maximum %d characters",
                                text.length(), properties.getMaxTextLength())
                );
            }
        });
    }

    /**
     * Maps AWS response to generic response.
     */
    private EntityDetectionResponse mapToEntityDetectionResponse(DetectEntitiesV2Response awsResponse) {
        List<DetectedEntity> entities = awsResponse.entities().stream()
                .map(this::mapToDetectedEntity)
                .collect(Collectors.toList());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("modelVersion", awsResponse.modelVersion());
        metadata.put("paginationToken", awsResponse.paginationToken());

        return new EntityDetectionResponse(entities, awsResponse.modelVersion(), metadata);
    }

    /**
     * Maps AWS entity to generic entity.
     */
    private DetectedEntity mapToDetectedEntity(Entity awsEntity) {
        List<EntityAttribute> attributes = awsEntity.attributes() != null
                ? awsEntity.attributes().stream().map(this::mapToEntityAttribute).collect(Collectors.toList())
                : List.of();

        List<EntityTrait> traits = awsEntity.traits() != null
                ? awsEntity.traits().stream().map(this::mapToEntityTrait).collect(Collectors.toList())
                : List.of();

        return new DetectedEntity(
                awsEntity.id(),
                awsEntity.text(),
                awsEntity.categoryAsString(),
                awsEntity.typeAsString(),
                awsEntity.score() != null ? awsEntity.score().doubleValue() : 0.0,
                awsEntity.beginOffset(),
                awsEntity.endOffset(),
                attributes,
                traits
        );
    }

    private EntityAttribute mapToEntityAttribute(Attribute awsAttr) {
        List<EntityTrait> traits = awsAttr.traits() != null
                ? awsAttr.traits().stream().map(this::mapToEntityTrait).collect(Collectors.toList())
                : List.of();

        return new EntityAttribute(
                awsAttr.typeAsString(),
                awsAttr.score() != null ? awsAttr.score().doubleValue() : 0.0,
                awsAttr.relationshipScore() != null ? awsAttr.relationshipScore().doubleValue() : 0.0,
                awsAttr.id(),
                awsAttr.beginOffset(),
                awsAttr.endOffset(),
                awsAttr.text(),
                traits
        );
    }

    private EntityTrait mapToEntityTrait(Trait awsTrait) {
        return new EntityTrait(
                awsTrait.nameAsString(),
                awsTrait.score() != null ? awsTrait.score().doubleValue() : 0.0
        );
    }
}
