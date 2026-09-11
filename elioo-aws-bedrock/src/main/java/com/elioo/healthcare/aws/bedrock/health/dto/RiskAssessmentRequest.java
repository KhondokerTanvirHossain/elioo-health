package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.Map;

/**
 * Request for clinical risk assessment.
 *
 * @param medicalData       Medical data for risk assessment
 * @param patientContext    Patient context for personalized risk assessment
 * @param options           Risk assessment options
 */
public record RiskAssessmentRequest(
        Map<String, Object> medicalData,
        PatientContext patientContext,
        RiskAssessmentOptions options
) {
    /**
     * Create a simple risk assessment request with default options.
     */
    public static RiskAssessmentRequest simple(
            Map<String, Object> medicalData,
            PatientContext patientContext
    ) {
        return new RiskAssessmentRequest(
                medicalData,
                patientContext,
                RiskAssessmentOptions.defaultOptions()
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return medicalData != null && !medicalData.isEmpty();
    }
}
