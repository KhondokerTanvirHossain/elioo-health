package com.elioo.healthcare.llm.health.dto;

/**
 * Risk score for a specific risk category.
 *
 * @param category      Risk category (e.g., "cardiovascular", "metabolic", "renal")
 * @param level         Risk level: "LOW", "MODERATE", "HIGH", "CRITICAL"
 * @param score         Numerical score if available (0.0-1.0 or other scale)
 * @param explanation   Explanation of the risk assessment
 */
public record RiskScore(
        String category,
        String level,
        Double score,
        String explanation
) {
    /**
     * Check if risk is elevated (HIGH or CRITICAL).
     */
    public boolean isElevated() {
        return "HIGH".equalsIgnoreCase(level) || "CRITICAL".equalsIgnoreCase(level);
    }

    /**
     * Check if risk is critical.
     */
    public boolean isCritical() {
        return "CRITICAL".equalsIgnoreCase(level);
    }
}
