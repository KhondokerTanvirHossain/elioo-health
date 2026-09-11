package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;
import java.util.Map;

/**
 * Response containing comprehensive clinical insights.
 *
 * @param summary               Executive summary of findings
 * @param keyFindings           List of key clinical findings with severity levels
 * @param recommendations       List of evidence-based recommendations
 * @param riskAssessment        Risk assessment across organ systems
 * @param actionPlan            Prioritized action plan with timeframes
 * @param confidence            AI confidence score (0.0 - 1.0)
 * @param metadata              Additional metadata (model info, processing time, etc.)
 */
public record ClinicalInsightResponse(
        String summary,
        List<ClinicalFinding> keyFindings,
        List<ClinicalRecommendation> recommendations,
        RiskAssessment riskAssessment,
        ActionPlan actionPlan,
        Double confidence,
        Map<String, Object> metadata
) {
    /**
     * Check if response has findings.
     */
    public boolean hasFindings() {
        return keyFindings != null && !keyFindings.isEmpty();
    }

    /**
     * Check if response has recommendations.
     */
    public boolean hasRecommendations() {
        return recommendations != null && !recommendations.isEmpty();
    }

    /**
     * Check if response has risk assessment.
     */
    public boolean hasRiskAssessment() {
        return riskAssessment != null;
    }

    /**
     * Check if response has action plan.
     */
    public boolean hasActionPlan() {
        return actionPlan != null;
    }

    /**
     * Get number of critical findings.
     */
    public long getCriticalFindingsCount() {
        return keyFindings != null ?
                keyFindings.stream().filter(ClinicalFinding::isCritical).count() : 0;
    }

    /**
     * Get number of urgent recommendations.
     */
    public long getUrgentRecommendationsCount() {
        return recommendations != null ?
                recommendations.stream().filter(ClinicalRecommendation::isUrgent).count() : 0;
    }
}
