package com.elioo.healthcare.gcp.vision.dto;

/**
 * Request DTO for image quality validation before OCR processing.
 *
 * <p>Validates image quality to ensure it meets minimum requirements for accurate
 * text extraction. Checks image size, format, and quality indicators.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * POST /api/gcp/vision/validate-image
 * {
 *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA..."
 * }
 * }</pre>
 *
 * @param imageBase64 Base64-encoded image data to validate
 *
 * @since 1.0.0
 */
public record ValidateImageRequest(String imageBase64) {
}
