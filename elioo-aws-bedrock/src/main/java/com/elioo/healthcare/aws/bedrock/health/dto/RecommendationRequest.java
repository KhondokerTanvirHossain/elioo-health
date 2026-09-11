package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.Map;

/**
 * Request for generating clinical recommendations.
 *
 * @param medicalFindings    Medical findings requiring recommendations
 * @param patientContext     Patient context for personalized recommendations
 * @param options            Recommendation generation options
 */
public record RecommendationRequest(
        Map<String, Object> medicalFindings,
        PatientContext patientContext,
        RecommendationOptions options
) {
    /**
     * Create a simple recommendation request with default options.
     */
    public static RecommendationRequest simple(
            Map<String, Object> medicalFindings,
            PatientContext patientContext
    ) {
        return new RecommendationRequest(
                medicalFindings,
                patientContext,
                RecommendationOptions.defaultOptions()
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return medicalFindings != null && !medicalFindings.isEmpty();
    }
}
