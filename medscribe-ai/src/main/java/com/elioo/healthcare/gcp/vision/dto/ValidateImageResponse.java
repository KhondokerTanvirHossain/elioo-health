package com.elioo.healthcare.gcp.vision.dto;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for image quality validation.
 *
 * <p>Contains validation results including whether the image is suitable for OCR,
 * any quality issues found, and quality metrics.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * // Valid image
 * {
 *   "isValid": true,
 *   "issues": [],
 *   "qualityMetrics": {
 *     "minConfidence": 0.80,
 *     "imageSize": 1024567
 *   }
 * }
 *
 * // Invalid image (too large)
 * {
 *   "isValid": false,
 *   "issues": ["Image size exceeds 20 MB limit"],
 *   "qualityMetrics": {
 *     "minConfidence": 0.80,
 *     "imageSize": 25000000
 *   }
 * }
 * }</pre>
 *
 * @param isValid        Whether the image passes quality validation
 * @param issues         List of quality issues found (empty if valid)
 * @param qualityMetrics Quality metrics (minConfidence, imageSize, etc.)
 *
 * @since 1.0.0
 */
public record ValidateImageResponse(
        boolean isValid,
        List<String> issues,
        Map<String, Object> qualityMetrics
) {
}
