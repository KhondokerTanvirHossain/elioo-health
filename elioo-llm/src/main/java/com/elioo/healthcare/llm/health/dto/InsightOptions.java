package com.elioo.healthcare.llm.health.dto;

/**
 * Options for clinical insight generation.
 *
 * @param targetAudience                Target audience for the content
 * @param includeRiskAssessment         Whether to include risk assessment
 * @param includeRecommendations        Whether to include recommendations
 * @param includeEducationalContent     Whether to include educational content
 * @param focusArea                     Optional focus area (e.g., "cardiovascular", "metabolic")
 * @param maxRecommendations            Maximum number of recommendations to generate
 */
public record InsightOptions(
        TargetAudience targetAudience,
        boolean includeRiskAssessment,
        boolean includeRecommendations,
        boolean includeEducationalContent,
        String focusArea,
        Integer maxRecommendations
) {
    /**
     * Create default options for patient audience with all features.
     */
    public static InsightOptions defaultPatient() {
        return new InsightOptions(
                TargetAudience.PATIENT,
                true,
                true,
                true,
                null,
                10
        );
    }

    /**
     * Create default options for provider audience with all features.
     */
    public static InsightOptions defaultProvider() {
        return new InsightOptions(
                TargetAudience.PROVIDER,
                true,
                true,
                false, // No educational content for providers
                null,
                15
        );
    }

    /**
     * Create minimal options (summary only, no extras).
     */
    public static InsightOptions minimal() {
        return new InsightOptions(
                TargetAudience.PATIENT,
                false,
                false,
                false,
                null,
                null
        );
    }
}
