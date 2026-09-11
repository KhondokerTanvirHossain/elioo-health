package com.elioo.healthcare.aws.comprehendmedical.model;

/**
 * Generic request for medical entity detection.
 *
 * @param text                  Medical text to analyze (max 20,000 characters)
 * @param detectPhi             Whether to detect Protected Health Information
 * @param extractRelationships  Whether to extract entity relationships
 */
public record EntityDetectionRequest(
        String text,
        boolean detectPhi,
        boolean extractRelationships
) {
    /**
     * Creates a standard entity detection request.
     */
    public static EntityDetectionRequest standard(String text) {
        return new EntityDetectionRequest(text, false, true);
    }

    /**
     * Creates a PHI detection request.
     */
    public static EntityDetectionRequest withPhi(String text) {
        return new EntityDetectionRequest(text, true, true);
    }
}
