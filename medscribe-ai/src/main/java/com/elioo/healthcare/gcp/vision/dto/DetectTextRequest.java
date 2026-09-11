package com.elioo.healthcare.gcp.vision.dto;

/**
 * Request DTO for simple text detection using Google Cloud Vision API.
 *
 * <p>This is a lightweight request for extracting plain text from an image
 * without structured analysis. For detailed OCR with block/paragraph structure,
 * use {@link AnalyzeDocumentRequest}.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * POST /api/gcp/vision/detect-text
 * {
 *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA..."
 * }
 * }</pre>
 *
 * @param imageBase64 Base64-encoded image data (JPEG, PNG, BMP, GIF, etc.)
 *
 * @since 1.0.0
 */
public record DetectTextRequest(String imageBase64) {
}
