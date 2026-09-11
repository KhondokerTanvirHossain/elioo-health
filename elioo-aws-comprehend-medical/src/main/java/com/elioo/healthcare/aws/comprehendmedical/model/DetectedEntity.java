package com.elioo.healthcare.aws.comprehendmedical.model;

import java.util.List;

/**
 * Generic representation of a detected medical entity.
 *
 * @param id           Unique identifier
 * @param text         Entity text
 * @param category     Entity category (MEDICATION, MEDICAL_CONDITION, ANATOMY, etc.)
 * @param type         Specific entity type (BRAND_NAME, DX_NAME, SYSTEM_ORGAN_SITE, etc.)
 * @param score        Confidence score (0.0 - 1.0)
 * @param beginOffset  Start position in text
 * @param endOffset    End position in text
 * @param attributes   List of entity attributes
 * @param traits       List of entity traits (NEGATION, DIAGNOSIS, SIGN, SYMPTOM, etc.)
 */
public record DetectedEntity(
        Integer id,
        String text,
        String category,
        String type,
        Double score,
        Integer beginOffset,
        Integer endOffset,
        List<EntityAttribute> attributes,
        List<EntityTrait> traits
) {
    public boolean hasHighConfidence(double threshold) {
        return score != null && score >= threshold;
    }

    public boolean isNegated() {
        return traits != null && traits.stream()
                .anyMatch(trait -> "NEGATION".equals(trait.name()));
    }

    public boolean isMedication() {
        return "MEDICATION".equalsIgnoreCase(category);
    }

    public boolean isMedicalCondition() {
        return "MEDICAL_CONDITION".equalsIgnoreCase(category);
    }

    public boolean isAnatomy() {
        return "ANATOMY".equalsIgnoreCase(category);
    }

    public boolean isTestTreatmentProcedure() {
        return "TEST_TREATMENT_PROCEDURE".equalsIgnoreCase(category);
    }

    public boolean isProtectedHealthInformation() {
        return "PROTECTED_HEALTH_INFORMATION".equalsIgnoreCase(category);
    }
}
