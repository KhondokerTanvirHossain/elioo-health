package com.elioo.healthcare.aws.comprehend.dto;

/**
 * Request DTO for Comprehend Medical detect entities endpoint.
 *
 * @param text Medical text to analyze
 * @param detectPhi Whether to detect Protected Health Information (PHI). Defaults to false.
 */
public record DetectEntitiesApiRequest(
        String text,
        Boolean detectPhi
) {
    public static DetectEntitiesApiRequest simple(String text) {
        return new DetectEntitiesApiRequest(text, false);
    }

    public static DetectEntitiesApiRequest withPhi(String text) {
        return new DetectEntitiesApiRequest(text, true);
    }
}
