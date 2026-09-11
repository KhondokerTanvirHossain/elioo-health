package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;

/**
 * Options for risk assessment generation.
 *
 * @param riskCategories                 Specific risk categories to assess (e.g., "cardiovascular", "metabolic", "renal")
 * @param includePreventionStrategies    Whether to include prevention strategies
 * @param timeHorizon                    Risk assessment time horizon in months
 */
public record RiskAssessmentOptions(
        List<String> riskCategories,
        boolean includePreventionStrategies,
        Integer timeHorizon
) {
    /**
     * Create default risk assessment options (all categories, with prevention).
     */
    public static RiskAssessmentOptions defaultOptions() {
        return new RiskAssessmentOptions(
                null, // Assess all categories
                true,
                12 // 12-month horizon
        );
    }

    /**
     * Create options for specific risk categories.
     */
    public static RiskAssessmentOptions forCategories(List<String> categories) {
        return new RiskAssessmentOptions(categories, true, 12);
    }

    /**
     * Check if specific categories are specified.
     */
    public boolean hasSpecificCategories() {
        return riskCategories != null && !riskCategories.isEmpty();
    }
}
