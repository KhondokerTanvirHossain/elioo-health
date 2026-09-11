package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityDetectionRequest;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AWS Comprehend Medical implementation of MedicalClassificationPort using the Comprehend Medical Service library.
 *
 * <p>This adapter demonstrates the power of the elioo-aws-comprehend-medical library:</p>
 * <ul>
 *   <li>✅ <b>No manual AWS SDK calls</b> - library handles it</li>
 *   <li>✅ <b>Clean DTO mapping</b> - library DTOs to domain objects</li>
 *   <li>✅ <b>Simplified error handling</b> - library provides consistent errors</li>
 *   <li>✅ <b>Reusable across applications</b> - not tied to medscribe-ai</li>
 * </ul>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements the business-defined port interface ({@link MedicalClassificationPort})</li>
 *   <li>Delegates to {@link ComprehendMedicalService} from library</li>
 *   <li>Maps between medscribe-ai domain objects and library DTOs</li>
 *   <li>Acts as Anti-Corruption Layer between domain and library</li>
 * </ul>
 *
 * <p>Supported Medical Code Systems:</p>
 * <ul>
 *   <li><b>ICD-10-CM:</b> Diagnosis codes (via {@code inferICD10Codes} API)</li>
 *   <li><b>RxNorm:</b> Medication codes (via {@code inferRxNormCodes} API)</li>
 *   <li><b>SNOMED-CT:</b> Not directly available via AWS Comprehend Medical API</li>
 *   <li><b>LOINC:</b> Not directly available via AWS Comprehend Medical API</li>
 * </ul>
 *
 * @see MedicalClassificationPort
 * @see ComprehendMedicalService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComprehendMedicalAdapter implements MedicalClassificationPort {

    private final ComprehendMedicalService comprehendMedicalService;

    @Value("${aws.comprehendmedical.min-confidence-threshold:0.70}")
    private double minConfidenceThreshold;

    @Value("${aws.comprehendmedical.max-text-length:20000}")
    private int maxTextLength;

    @Value("${aws.comprehendmedical.max-codes-per-concept:3}")
    private int maxCodesPerConcept;

    @Value("${aws.comprehendmedical.max-entities:50}")
    private int maxEntities;

    @Override
    public Mono<ClassificationResult> classifyMedicalEntities(ClassificationRequest request) {
        log.info("Starting medical entity classification for text length: {}", request.text().length());

        if (request.text().length() > maxTextLength) {
            return Mono.error(new ClassificationException(
                    "Text exceeds maximum length of " + maxTextLength + " characters"
            ));
        }

        // Map to library DTO
        EntityDetectionRequest libraryRequest = EntityDetectionRequest.standard(request.text());

        // Call library (handles AWS SDK, invocation, parsing)
        return comprehendMedicalService.detectEntities(libraryRequest)
                .flatMap(libraryResponse -> {
                    List<MedicalEntity> entities = mapToMedicalEntities(libraryResponse.entities());

                    // Extract relationships if requested
                    Mono<List<EntityRelationship>> relationshipsMono = request.extractRelationships()
                            ? extractRelationships(entities)
                            : Mono.just(List.of());

                    // Map to medical codes if code systems are requested
                    Mono<Map<String, List<MedicalCode>>> codesMono =
                            request.requestedCodeSystems() != null && !request.requestedCodeSystems().isEmpty()
                            ? mapCodeSystemsToMedicalCodes(request.text(), request.requestedCodeSystems())
                            : Mono.just(new HashMap<>());

                    return Mono.zip(relationshipsMono, codesMono)
                            .map(tuple -> new ClassificationResult(
                                    entities,
//                                    tuple.getT1(),
                                    tuple.getT2(),
                                    calculateOverallConfidence(entities)
//                                    Map.of(
//                                            "modelVersion", libraryResponse.modelVersion(),
//                                            "entityCount", libraryResponse.getEntityCount()
//                                    )
                            ));
                })
                .doOnSuccess(result -> log.info("Classification completed. Entities found: {}",
                        result.entities().size()))
                .onErrorResume(error -> {
                    log.error("Error classifying medical entities", error);
                    return Mono.error(new ClassificationException("Failed to classify medical entities", error));
                });
    }

    @Override
    public Mono<List<MedicalCode>> mapToMedicalCodes(String medicalTerm, List<String> codeSystems) {
        log.info("Mapping medical term to codes: {}", medicalTerm);

        return mapCodeSystemsToMedicalCodes(medicalTerm, codeSystems)
                .map(codeMap -> codeMap.values().stream()
                        .flatMap(List::stream)
                        .collect(Collectors.toList())
                );
    }

    @Override
    public Mono<List<EntityRelationship>> extractRelationships(List<MedicalEntity> entities) {
        log.info("Extracting relationships between {} entities", entities.size());

        // Relationships are already extracted from AWS response
        // This method provides additional relationship inference if needed
        List<EntityRelationship> relationships = new ArrayList<>();

        // Build relationships from entity attributes
        for (MedicalEntity entity : entities) {
            if (entity.attributes() != null) {
                for (EntityAttribute attr : entity.attributes()) {
                    relationships.add(new EntityRelationship(
                            attr.id(),
                            attr.type(),
                            attr.relationshipScore(),
                            entity.id(),
                            attr.id()
                    ));
                }
            }
        }

        return Mono.just(relationships);
    }

    @Override
    public Mono<ValidationResult> validateClassification(ClassificationResult result) {
        log.info("Validating classification result");

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        double qualityScore = result.overallConfidence();

        if (result.entities().isEmpty()) {
            warnings.add("No medical entities detected in text");
            qualityScore -= 0.2;
        }

        if (result.overallConfidence() < minConfidenceThreshold) {
            warnings.add("Overall confidence below threshold");
            qualityScore -= 0.1;
        }

        long lowConfidenceEntities = result.entities().stream()
                .filter(e -> e.score() < minConfidenceThreshold)
                .count();

        if (lowConfidenceEntities > result.entities().size() / 2) {
            warnings.add("More than 50% of entities have low confidence");
            qualityScore -= 0.15;
        }

        boolean isValid = errors.isEmpty() && qualityScore >= 0.5;

        return Mono.just(new ValidationResult(
                isValid,
                Math.max(0.0, qualityScore),
                warnings,
                errors
        ));
    }

    // ========================================================================
    // Mapping Methods: library DTOs ↔ medscribe-ai domain
    // ========================================================================

    /**
     * Map code systems to medical codes using library services.
     */
    private Mono<Map<String, List<MedicalCode>>> mapCodeSystemsToMedicalCodes(
            String text,
            List<String> codeSystems
    ) {
        List<Mono<Map<String, List<MedicalCode>>>> codeMonos = new ArrayList<>();

        for (String codeSystem : codeSystems) {
            switch (codeSystem.toUpperCase()) {
                case "ICD10", "ICD-10" -> codeMonos.add(
                        comprehendMedicalService.inferICD10Codes(text)
                                .map(codes -> Map.of("ICD10", mapLibraryMedicalCodes(codes)))
                );
                case "RXNORM" -> codeMonos.add(
                        comprehendMedicalService.inferRxNormCodes(text)
                                .map(codes -> Map.of("RXNORM", mapLibraryMedicalCodes(codes)))
                );
                case "SNOMED", "SNOMED-CT", "SNOMEDCT" -> codeMonos.add(
                        comprehendMedicalService.inferSNOMEDCTCodes(text)
                                .map(codes -> Map.of("SNOMEDCT", mapLibraryMedicalCodes(codes)))
                                .doOnSuccess(result -> log.info("Inferred {} SNOMED-CT codes", result.get("SNOMEDCT").size()))
                                .onErrorResume(error -> {
                                    log.warn("SNOMED-CT inference failed: {}", error.getMessage());
                                    return Mono.just(Map.of("SNOMEDCT", List.<MedicalCode>of()));
                                })
                );
                default -> log.warn("Unsupported code system: {}", codeSystem);
            }
        }

        if (codeMonos.isEmpty()) {
            return Mono.just(new HashMap<>());
        }

        return Mono.zip(codeMonos, arrays -> {
            Map<String, List<MedicalCode>> allCodes = new HashMap<>();
            for (Object obj : arrays) {
                @SuppressWarnings("unchecked")
                Map<String, List<MedicalCode>> codeMap = (Map<String, List<MedicalCode>>) obj;
                allCodes.putAll(codeMap);
            }
            return allCodes;
        });
    }

    /**
     * Map library MedicalCode list to domain MedicalCode list.
     * Filters by confidence threshold, sorts by score descending, and limits to maxCodesPerConcept.
     */
    private List<MedicalCode> mapLibraryMedicalCodes(
            List<com.elioo.healthcare.aws.comprehendmedical.model.MedicalCode> libraryCodes
    ) {
        var stream = libraryCodes.stream()
                // Filter by minimum confidence threshold
                .filter(code -> code.score() != null && code.score() >= minConfidenceThreshold)
                // Sort by score descending (highest confidence first)
                .sorted(Comparator.comparingDouble(
                        (com.elioo.healthcare.aws.comprehendmedical.model.MedicalCode c) ->
                                c.score() != null ? c.score() : 0.0
                ).reversed())
                .map(libraryCode -> new MedicalCode(
                        libraryCode.code(),
                        libraryCode.description(),
                        libraryCode.codeSystem(),
                        libraryCode.score() != null ? libraryCode.score().floatValue() : 0.0f
                ));

        // Apply limit if configured (0 means unlimited)
        if (maxCodesPerConcept > 0) {
            stream = stream.limit(maxCodesPerConcept);
        }

        return stream.collect(Collectors.toList());
    }

    /**
     * Map library DetectedEntity list to medscribe-ai MedicalEntity list.
     * Filters by confidence threshold, sorts by score descending, and limits to maxEntities.
     */
    private List<MedicalEntity> mapToMedicalEntities(
            List<com.elioo.healthcare.aws.comprehendmedical.model.DetectedEntity> libraryEntities
    ) {
        var stream = libraryEntities.stream()
                // Filter by minimum confidence threshold
                .filter(entity -> entity.score() != null && entity.score() >= minConfidenceThreshold)
                // Sort by score descending (highest confidence first)
                .sorted(Comparator.comparingDouble(
                        (com.elioo.healthcare.aws.comprehendmedical.model.DetectedEntity e) ->
                                e.score() != null ? e.score() : 0.0
                ).reversed())
                .map(this::mapToMedicalEntity);

        // Apply limit if configured (0 means unlimited)
        if (maxEntities > 0) {
            stream = stream.limit(maxEntities);
        }

        return stream.collect(Collectors.toList());
    }

    /**
     * Map a single library DetectedEntity to medscribe-ai MedicalEntity.
     */
    private MedicalEntity mapToMedicalEntity(
            com.elioo.healthcare.aws.comprehendmedical.model.DetectedEntity libraryEntity
    ) {
        List<EntityAttribute> attributes = libraryEntity.attributes() != null
                ? libraryEntity.attributes().stream()
                        .map(this::mapToEntityAttribute)
                        .collect(Collectors.toList())
                : List.of();

        List<EntityTrait> traits = libraryEntity.traits() != null
                ? libraryEntity.traits().stream()
                        .map(this::mapToEntityTrait)
                        .collect(Collectors.toList())
                : List.of();

        return new MedicalEntity(
                libraryEntity.id() != null ? libraryEntity.id() : 0,
                libraryEntity.text(),
                libraryEntity.category(),
                libraryEntity.type(),
                libraryEntity.score() != null ? libraryEntity.score() : 0.0,
//                libraryEntity.beginOffset(),
//                libraryEntity.endOffset(),
                attributes
//                traits
        );
    }

    /**
     * Map library EntityAttribute to medscribe-ai EntityAttribute.
     */
    private EntityAttribute mapToEntityAttribute(
            com.elioo.healthcare.aws.comprehendmedical.model.EntityAttribute libraryAttr
    ) {
        List<EntityTrait> traits = libraryAttr.traits() != null
                ? libraryAttr.traits().stream()
                        .map(this::mapToEntityTrait)
                        .collect(Collectors.toList())
                : List.of();

        return new EntityAttribute(
                libraryAttr.type(),
                libraryAttr.score(),
                libraryAttr.relationshipScore(),
                libraryAttr.id() != null ? libraryAttr.id() : 0,
//                libraryAttr.beginOffset(),
//                libraryAttr.endOffset(),
                libraryAttr.text()
//                traits
        );
    }

    /**
     * Map library EntityTrait to medscribe-ai EntityTrait.
     */
    private EntityTrait mapToEntityTrait(
            com.elioo.healthcare.aws.comprehendmedical.model.EntityTrait libraryTrait
    ) {
        return new EntityTrait(
                libraryTrait.name(),
                libraryTrait.score()
        );
    }

    /**
     * Calculate overall confidence from entities.
     */
    private double calculateOverallConfidence(List<MedicalEntity> entities) {
        if (entities.isEmpty()) {
            return 0.0;
        }

        return entities.stream()
                .mapToDouble(MedicalEntity::score)
                .average()
                .orElse(0.0);
    }

    /**
     * Custom exception for classification operations.
     */
    public static class ClassificationException extends RuntimeException {
        public ClassificationException(String message) {
            super(message);
        }

        public ClassificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
