package com.elioo.healthcare.aws.textract.model;

import java.util.Map;

/**
 * Result of image quality validation.
 *
 * Used to validate images before sending to Textract to avoid unnecessary API calls
 * and provide early feedback on image quality issues.
 *
 * @param isValid       Whether the image passes validation
 * @param qualityScore  Quality score (0.0 - 1.0)
 * @param message       Validation message (success or failure reason)
 * @param metrics       Detailed metrics (size, resolution, format, etc.)
 */
public record ImageQualityResult(
        boolean isValid,
        double qualityScore,
        String message,
        Map<String, Object> metrics
) {
    /**
     * Creates a successful validation result.
     */
    public static ImageQualityResult valid(double qualityScore, Map<String, Object> metrics) {
        return new ImageQualityResult(true, qualityScore, "Image quality acceptable", metrics);
    }

    /**
     * Creates a failed validation result.
     */
    public static ImageQualityResult invalid(String reason, Map<String, Object> metrics) {
        return new ImageQualityResult(false, 0.0, reason, metrics);
    }

    /**
     * Creates a warning validation result (valid but with quality concerns).
     */
    public static ImageQualityResult warning(double qualityScore, String message, Map<String, Object> metrics) {
        return new ImageQualityResult(true, qualityScore, message, metrics);
    }
}
