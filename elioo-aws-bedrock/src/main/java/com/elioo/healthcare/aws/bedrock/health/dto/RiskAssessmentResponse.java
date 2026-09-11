package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;

/**
 * Response containing clinical risk assessment results.
 *
 * @param riskAssessment           Detailed risk assessment with scores
 * @param preventionStrategies     List of prevention strategies
 * @param overallAssessment        Overall risk assessment summary
 */
public record RiskAssessmentResponse(
        RiskAssessment riskAssessment,
        List<String> preventionStrategies,
        String overallAssessment
) {
    /**
     * Check if prevention strategies are included.
     */
    public boolean hasPreventionStrategies() {
        return preventionStrategies != null && !preventionStrategies.isEmpty();
    }

    /**
     * Check if risk is elevated.
     */
    public boolean isElevatedRisk() {
        return riskAssessment != null &&
                (riskAssessment.isHighRisk() || riskAssessment.hasElevatedRisks());
    }
}
