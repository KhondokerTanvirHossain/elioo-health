package com.elioo.healthcare.llm.health.dto;

import java.util.List;
import java.util.Map;

/**
 * Represents a clinical finding from medical data analysis.
 *
 * @param id                    Unique identifier for this finding
 * @param description           Human-readable description of the finding
 * @param category              Category of finding (e.g., "LAB_ABNORMALITY", "RISK_FACTOR")
 * @param severity              Severity level: "LOW", "MODERATE", "HIGH", "CRITICAL"
 * @param explanation           Detailed explanation of clinical significance
 * @param relatedTests          List of related test names/identifiers
 * @param details               Additional finding-specific details
 */
public record ClinicalFinding(
        String id,
        String description,
        String category,
        String severity,
        String explanation,
        List<String> relatedTests,
        Map<String, Object> details
) {
    /**
     * Check if finding is critical.
     */
    public boolean isCritical() {
        return "CRITICAL".equalsIgnoreCase(severity);
    }

    /**
     * Check if finding is high severity.
     */
    public boolean isHighSeverity() {
        return "HIGH".equalsIgnoreCase(severity);
    }

    /**
     * Check if finding requires attention (HIGH or CRITICAL).
     */
    public boolean requiresAttention() {
        return isCritical() || isHighSeverity();
    }
}
