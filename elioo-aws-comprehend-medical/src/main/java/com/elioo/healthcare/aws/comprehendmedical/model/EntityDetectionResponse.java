package com.elioo.healthcare.aws.comprehendmedical.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response from medical entity detection.
 *
 * @param entities    List of detected entities
 * @param modelVersion Model version used
 * @param metadata    Additional metadata
 */
public record EntityDetectionResponse(
        List<DetectedEntity> entities,
        String modelVersion,
        Map<String, Object> metadata
) {
    public List<DetectedEntity> getEntitiesByCategory(String category) {
        return entities.stream()
                .filter(e -> category.equalsIgnoreCase(e.category()))
                .collect(Collectors.toList());
    }

    public List<DetectedEntity> getMedications() {
        return getEntitiesByCategory("MEDICATION");
    }

    public List<DetectedEntity> getMedicalConditions() {
        return getEntitiesByCategory("MEDICAL_CONDITION");
    }

    public List<DetectedEntity> getAnatomies() {
        return getEntitiesByCategory("ANATOMY");
    }

    public List<DetectedEntity> getTestsTreatmentsProcedures() {
        return getEntitiesByCategory("TEST_TREATMENT_PROCEDURE");
    }

    public List<DetectedEntity> getProtectedHealthInformation() {
        return getEntitiesByCategory("PROTECTED_HEALTH_INFORMATION");
    }

    public List<DetectedEntity> getHighConfidenceEntities(double threshold) {
        return entities.stream()
                .filter(e -> e.hasHighConfidence(threshold))
                .collect(Collectors.toList());
    }

    public boolean hasEntities() {
        return entities != null && !entities.isEmpty();
    }

    public int getEntityCount() {
        return entities != null ? entities.size() : 0;
    }
}
