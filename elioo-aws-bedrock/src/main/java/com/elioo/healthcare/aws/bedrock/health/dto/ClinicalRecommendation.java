package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Represents a clinical recommendation based on findings.
 *
 * @param id                Unique identifier for this recommendation
 * @param category          Category: "IMMEDIATE_ACTION", "DIAGNOSTIC_TESTS", "MEDICATION", etc.
 * @param priority          Priority level: "URGENT", "HIGH", "MEDIUM", "LOW"
 * @param recommendation    The actual recommendation text
 * @param rationale         Explanation/reasoning for the recommendation
 * @param evidenceLevel     Evidence-based medicine level: "A", "B", "C"
 * @param timeframe         Recommended timeframe: "Immediate", "Within 24 hours", "1-2 weeks", etc.
 */
public record ClinicalRecommendation(
        String id,
        String category,
        String priority,
        String recommendation,
        String rationale,
        String evidenceLevel,
        String timeframe
) {
    /**
     * Check if recommendation is urgent.
     */
    public boolean isUrgent() {
        return "URGENT".equalsIgnoreCase(priority);
    }

    /**
     * Check if recommendation requires immediate action.
     */
    public boolean isImmediateAction() {
        return "IMMEDIATE_ACTION".equalsIgnoreCase(category);
    }

    /**
     * Check if recommendation is high priority (URGENT or HIGH).
     */
    public boolean isHighPriority() {
        return isUrgent() || "HIGH".equalsIgnoreCase(priority);
    }
}
