package com.elioo.healthcare.gcp.vision.dto;

import java.util.List;

/**
 * Request DTO for comprehensive document analysis using Google Cloud Vision API.
 *
 * <p>Performs full OCR with structured text extraction including blocks, paragraphs,
 * words, and geometry information. Supports multi-language detection.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Bangla + English medical report
 * POST /api/gcp/vision/analyze-document
 * {
 *   "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
 *   "languageHints": ["bn", "en"]
 * }
 *
 * // Simple request with default language hints
 * var request = AnalyzeDocumentRequest.simple(imageBase64);
 * }</pre>
 *
 * @param imageBase64   Base64-encoded image data (up to 20 MB)
 * @param languageHints Optional language hints to improve accuracy (ISO 639-1 codes)
 *
 * @since 1.0.0
 */
public record AnalyzeDocumentRequest(
        String imageBase64,
        List<String> languageHints
) {
    /**
     * Create a simple request with default language hints for Bangladesh (Bangla + English).
     *
     * @param imageBase64 Base64-encoded image data
     * @return AnalyzeDocumentRequest with language hints ["bn", "en"]
     */
    public static AnalyzeDocumentRequest simple(String imageBase64) {
        return new AnalyzeDocumentRequest(imageBase64, List.of("bn", "en"));
    }

    /**
     * Create a request for English-only documents.
     *
     * @param imageBase64 Base64-encoded image data
     * @return AnalyzeDocumentRequest with language hint ["en"]
     */
    public static AnalyzeDocumentRequest englishOnly(String imageBase64) {
        return new AnalyzeDocumentRequest(imageBase64, List.of("en"));
    }

    /**
     * Create a request for Bangla-only documents.
     *
     * @param imageBase64 Base64-encoded image data
     * @return AnalyzeDocumentRequest with language hint ["bn"]
     */
    public static AnalyzeDocumentRequest banglaOnly(String imageBase64) {
        return new AnalyzeDocumentRequest(imageBase64, List.of("bn"));
    }
}
