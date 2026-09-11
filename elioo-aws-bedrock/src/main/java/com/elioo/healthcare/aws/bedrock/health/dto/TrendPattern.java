package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Represents a detected trend pattern in medical data.
 *
 * @param testName                 Name of the test showing the pattern
 * @param pattern                  Pattern type: "INCREASING", "DECREASING", "STABLE", "FLUCTUATING"
 * @param clinicalSignificance     Clinical interpretation of the pattern
 */
public record TrendPattern(
        String testName,
        String pattern,
        String clinicalSignificance
) {
    /**
     * Check if pattern is concerning (increasing or decreasing).
     */
    public boolean isConcerning() {
        return "INCREASING".equalsIgnoreCase(pattern) ||
                "DECREASING".equalsIgnoreCase(pattern);
    }

    /**
     * Check if pattern is stable.
     */
    public boolean isStable() {
        return "STABLE".equalsIgnoreCase(pattern);
    }
}
