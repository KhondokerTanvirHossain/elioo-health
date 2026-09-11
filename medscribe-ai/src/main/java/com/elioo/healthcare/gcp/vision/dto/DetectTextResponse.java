package com.elioo.healthcare.gcp.vision.dto;

/**
 * Response DTO for simple text detection.
 *
 * <p>Contains the extracted plain text and metadata about the detection.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * {
 *   "text": "Blood Pressure: 120/80 mmHg",
 *   "language": "en",
 *   "confidence": 0.95
 * }
 * }</pre>
 *
 * @param text       The complete extracted text from the image
 * @param language   Detected language code (e.g., "bn", "en") or "auto-detected"
 * @param confidence Overall confidence score (0.0 - 1.0)
 *
 * @since 1.0.0
 */
public record DetectTextResponse(
        String text,
        String language,
        double confidence
) {
}
