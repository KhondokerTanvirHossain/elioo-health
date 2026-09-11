package com.elioo.healthcare.medicalreport.application.port.out;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Outbound port for medical entity classification and coding operations.
 * This interface defines the contract for extracting medical entities and mapping them to standard codes.
 *
 * Implementation Note: This is a driven port in hexagonal architecture.
 * The business logic depends on this interface, not on specific NLP/classification providers.
 *
 * Possible implementations:
 * - AWS Comprehend Medical
 * - Azure Health Text Analytics
 * - Google Cloud Healthcare NLP API
 * - Custom ML models
 */
public interface MedicalClassificationPort {

    /**
     * Classify medical entities from extracted text data.
     * Identifies medical conditions, medications, tests, anatomy, and PHI.
     *
     * @param request Classification request containing text and options
     * @return Mono containing classification result with entities and codes
     */
    Mono<ClassificationResult> classifyMedicalEntities(ClassificationRequest request);

    /**
     * Map medical terms to standard coding systems.
     *
     * @param medicalTerm The medical term to map
     * @param codeSystems List of code systems to map to (ICD-10, LOINC, SNOMED, etc.)
     * @return Mono containing mapped medical codes
     */
    Mono<List<MedicalCode>> mapToMedicalCodes(String medicalTerm, List<String> codeSystems);

    /**
     * Extract relationships between medical entities.
     *
     * @param entities List of identified medical entities
     * @return Mono containing entity relationships
     */
    Mono<List<EntityRelationship>> extractRelationships(List<MedicalEntity> entities);

    /**
     * Validate classification confidence and quality.
     *
     * @param result Classification result to validate
     * @return Mono containing validation result
     */
    Mono<ValidationResult> validateClassification(ClassificationResult result);

    /**
     * Request object for medical classification.
     */
    record ClassificationRequest(
            String text,
            String language,
            List<String> requestedCodeSystems,
            Double confidenceThreshold,
            boolean extractRelationships,
            Map<String, Object> options
    ) {}

    /**
     * Result object containing classified medical entities.
     */
    record ClassificationResult(
            List<MedicalEntity> entities,
//            List<EntityRelationship> relationships,
            Map<String, List<MedicalCode>> medicalCodes,
            double overallConfidence
//            Map<String, Object> metadata
    ) {}

    /**
     * Medical entity identified in text.
     */
    record MedicalEntity(
            int id,
            String text,
            String category,
            String type,
            double score,
//            int beginOffset,
//            int endOffset,
            List<EntityAttribute> attributes
//            List<EntityTrait> traits
    ) {}

    /**
     * Attribute of a medical entity (e.g., dosage, frequency, direction).
     */
    record EntityAttribute(
            String type,
            double score,
            double relationshipScore,
            int id,
//            int beginOffset,
//            int endOffset,
            String text
//            List<EntityTrait> traits
    ) {}

    /**
     * Trait of a medical entity (e.g., NEGATION, DIAGNOSIS, SIGN, SYMPTOM).
     */
    record EntityTrait(
            String name,
            double score
    ) {}

    /**
     * Medical code from standard coding system.
     */
    record MedicalCode(
            String code,
            String description,
            String codeSystem,
            double score
    ) {}

    /**
     * Relationship between two medical entities.
     */
    record EntityRelationship(
            int id,
            String type,
            double score,
            int sourceId,
            int targetId
    ) {}

    /**
     * Validation result for classification quality.
     */
    record ValidationResult(
            boolean isValid,
            double qualityScore,
            List<String> warnings,
            List<String> errors
    ) {}
}
