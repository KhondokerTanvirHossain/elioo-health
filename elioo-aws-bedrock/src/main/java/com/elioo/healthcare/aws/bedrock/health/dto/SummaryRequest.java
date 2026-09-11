package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.Map;

/**
 * Request for generating medical data summary.
 *
 * @param medicalData       Medical data to summarize
 * @param patientContext    Patient context for personalization
 * @param options           Summary generation options
 */
public record SummaryRequest(
        Map<String, Object> medicalData,
        PatientContext patientContext,
        SummaryOptions options
) {
    /**
     * Create a simple summary request with default options.
     */
    public static SummaryRequest simple(Map<String, Object> medicalData) {
        return new SummaryRequest(
                medicalData,
                null,
                SummaryOptions.defaultPatient()
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return medicalData != null && !medicalData.isEmpty();
    }
}
