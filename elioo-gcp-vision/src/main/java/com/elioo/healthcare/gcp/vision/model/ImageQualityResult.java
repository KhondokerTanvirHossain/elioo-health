package com.elioo.healthcare.gcp.vision.model;

import java.util.Map;

/**
 * Result of image quality validation before OCR processing.
 *
 * <p>This record provides comprehensive quality metrics to determine if an image
 * is suitable for OCR processing.</p>
 *
 * <p><b>Quality Score Interpretation:</b></p>
 * <ul>
 *   <li><b>1.0:</b> Excellent quality - optimal size, high resolution</li>
 *   <li><b>0.8-0.9:</b> Good quality - acceptable for OCR</li>
 *   <li><b>0.6-0.7:</b> Fair quality - may have accuracy issues</li>
 *   <li><b>&lt; 0.6:</b> Poor quality - not recommended for OCR</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * ImageQualityResult result = visionService.validateImageQuality(imageBase64).block();
 *
 * if (!result.isValid()) {
 *     throw new ValidationException("Image validation failed: " + result.message());
 * }
 *
 * if (result.qualityScore() < 0.7) {
 *     log.warn("Low image quality detected: {}", result.qualityScore());
 * }
 *
 * // Check specific metrics
 * double sizeMb = (double) result.metrics().get("sizeInMB");
 * if (sizeMb > 15) {
 *     log.warn("Large image size: {} MB", sizeMb);
 * }
 * }</pre>
 *
 * @param isValid      Whether the image passes basic validation
 * @param qualityScore Quality score from 0.0 (poor) to 1.0 (excellent)
 * @param message      Human-readable message explaining the validation result
 * @param metrics      Detailed metrics (size, dimensions, format, etc.)
 *
 * @since 0.1.0
 */
public record ImageQualityResult(
        boolean isValid,
        double qualityScore,
        String message,
        Map<String, Object> metrics
) {
    /**
     * Create a result for a valid image.
     *
     * @param qualityScore Quality score (0.0 - 1.0)
     * @param metrics      Detailed metrics
     * @return Valid result
     */
    public static ImageQualityResult valid(double qualityScore, Map<String, Object> metrics) {
        return new ImageQualityResult(
                true,
                qualityScore,
                "Image quality is acceptable for OCR processing",
                metrics
        );
    }

    /**
     * Create a result for an invalid image.
     *
     * @param reason  Reason for invalidity
     * @param metrics Detailed metrics
     * @return Invalid result with quality score 0.0
     */
    public static ImageQualityResult invalid(String reason, Map<String, Object> metrics) {
        return new ImageQualityResult(
                false,
                0.0,
                reason,
                metrics
        );
    }

    /**
     * Create a result for a valid but low-quality image.
     *
     * @param qualityScore Quality score (0.0 - 1.0)
     * @param warning      Warning message
     * @param metrics      Detailed metrics
     * @return Valid result with warning
     */
    public static ImageQualityResult warning(double qualityScore, String warning, Map<String, Object> metrics) {
        return new ImageQualityResult(
                true,
                qualityScore,
                "Image is valid but has quality concerns: " + warning,
                metrics
        );
    }

    /**
     * Get image size in megabytes from metrics.
     *
     * @return Size in MB, or 0.0 if not available
     */
    public double getSizeInMB() {
        if (metrics != null && metrics.containsKey("sizeInMB")) {
            return ((Number) metrics.get("sizeInMB")).doubleValue();
        }
        return 0.0;
    }

    /**
     * Get image size in bytes from metrics.
     *
     * @return Size in bytes, or 0 if not available
     */
    public long getSizeInBytes() {
        if (metrics != null && metrics.containsKey("sizeBytes")) {
            return ((Number) metrics.get("sizeBytes")).longValue();
        }
        return 0L;
    }

    /**
     * Check if image size is within optimal range (1-15 MB).
     *
     * @return true if size is in optimal range
     */
    public boolean isOptimalSize() {
        double sizeMb = getSizeInMB();
        return sizeMb >= 1.0 && sizeMb <= 15.0;
    }

    /**
     * Check if quality meets a minimum threshold.
     *
     * @param threshold Minimum acceptable quality score
     * @return true if qualityScore >= threshold
     */
    public boolean meetsQualityThreshold(double threshold) {
        return qualityScore >= threshold;
    }
}
