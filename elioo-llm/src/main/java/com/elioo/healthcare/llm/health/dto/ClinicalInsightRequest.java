package com.elioo.healthcare.llm.health.dto;

import java.util.Map;

/**
 * Request for generating comprehensive clinical insights from medical data.
 *
 * @param medicalData       Structured medical data (test results, classifications, etc.)
 * @param patientContext    Patient demographics and clinical context
 * @param options           Options for insight generation
 */
public record ClinicalInsightRequest(
        Map<String, Object> medicalData,
        PatientContext patientContext,
        InsightOptions options
) {
    /**
     * Create a request with minimal patient context.
     */
    public static ClinicalInsightRequest simple(
            Map<String, Object> medicalData,
            Integer patientAge,
            String patientGender
    ) {
        return new ClinicalInsightRequest(
                medicalData,
                PatientContext.minimal(patientAge, patientGender),
                InsightOptions.defaultPatient()
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return medicalData != null && !medicalData.isEmpty();
    }
}
