package com.elioo.healthcare.llm.health.dto;

import java.util.List;
import java.util.Map;

/**
 * Comprehensive risk assessment across multiple organ systems.
 *
 * @param riskScores           Map of risk category to risk score
 * @param overallRiskLevel     Overall risk level: "LOW", "MODERATE", "HIGH"
 * @param riskFactors          List of identified risk factors
 */
public record RiskAssessment(
        Map<String, RiskScore> riskScores,
        String overallRiskLevel,
        List<String> riskFactors
) {
    /**
     * Check if overall risk is high.
     */
    public boolean isHighRisk() {
        return "HIGH".equalsIgnoreCase(overallRiskLevel);
    }

    /**
     * Check if any individual risk category is elevated.
     */
    public boolean hasElevatedRisks() {
        return riskScores != null &&
                riskScores.values().stream().anyMatch(RiskScore::isElevated);
    }

    /**
     * Get risk score for a specific category.
     */
    public RiskScore getRiskScore(String category) {
        return riskScores != null ? riskScores.get(category) : null;
    }
}
