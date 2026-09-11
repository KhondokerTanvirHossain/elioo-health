package com.elioo.healthcare.gcp.vision.model;

import java.util.List;

/**
 * Request object for Vision OCR operations.
 *
 * <p>This record encapsulates all parameters needed for Google Cloud Vision API
 * document text detection operations.</p>
 *
 * <p><b>Language Hints:</b></p>
 * <p>Language hints improve OCR accuracy, especially for non-Latin scripts like Bangla.
 * Provide language codes as ISO 639-1 (2-letter) codes:</p>
 * <ul>
 *   <li>bn - Bangla/Bengali</li>
 *   <li>en - English</li>
 *   <li>hi - Hindi</li>
 *   <li>es - Spanish</li>
 *   <li>See com.elioo.healthcare.gcp.vision.api.VisionService for full list</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // Standard English document
 * VisionOcrRequest request = VisionOcrRequest.standard(imageBase64);
 *
 * // Bangla + English medical report
 * VisionOcrRequest request = VisionOcrRequest.banglaEnglish(imageBase64);
 *
 * // Custom language combination
 * VisionOcrRequest request = VisionOcrRequest.withLanguages(imageBase64, List.of("hi", "en"));
 *
 * // Text-only extraction (no geometry)
 * VisionOcrRequest request = VisionOcrRequest.textOnly(imageBase64);
 * </pre>
 *
 * @param imageBase64 Base64-encoded image data (JPEG, PNG, GIF, BMP, WEBP, etc.)
 * @param languageHints List of expected language codes (ISO 639-1) for better accuracy.
 *                      Examples: ["en"], ["bn"], ["bn", "en"], ["hi", "en"]
 * @param includeConfidence Include confidence scores in response (default: true)
 * @param includeGeometry Include bounding box geometry in response (default: true)
 *
 * @since 0.1.0
 */
public record VisionOcrRequest(
        String imageBase64,
        List<String> languageHints,
        boolean includeConfidence,
        boolean includeGeometry
) {
    /**
     * Create request with standard English language hint.
     *
     * <p>Use this for English-only documents.</p>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Request configured for English OCR with full features
     */
    public static VisionOcrRequest standard(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("en"), true, true);
    }

    /**
     * Create request with custom language hints.
     *
     * <p>Use this for documents in specific languages or mixed-language documents.</p>
     *
     * <p><b>Best Practices:</b></p>
     * <ul>
     *   <li>List primary language first</li>
     *   <li>Include fallback languages (e.g., ["bn", "en"])</li>
     *   <li>Limit to 2-3 languages for best performance</li>
     * </ul>
     *
     * @param imageBase64 Base64-encoded image data
     * @param languages   List of ISO 639-1 language codes
     * @return Request configured with specified languages
     */
    public static VisionOcrRequest withLanguages(String imageBase64, List<String> languages) {
        return new VisionOcrRequest(imageBase64, languages, true, true);
    }

    /**
     * Create request optimized for Bangla + English documents.
     *
     * <p>Use this for medical reports, forms, or documents primarily in Bangla
     * with some English content.</p>
     *
     * <p><b>Common Use Cases:</b></p>
     * <ul>
     *   <li>Medical reports in Bangladesh</li>
     *   <li>Patient information with Bangla names</li>
     *   <li>Mixed Bangla-English test results</li>
     * </ul>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Request configured for Bangla + English OCR
     */
    public static VisionOcrRequest banglaEnglish(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("bn", "en"), true, true);
    }

    /**
     * Create request for text-only extraction (no geometry).
     *
     * <p>Use this when you only need plain text and don't need bounding boxes
     * or confidence scores. Slightly faster than full extraction.</p>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Feeding text to NLP models</li>
     *   <li>Simple text search/indexing</li>
     *   <li>Text length validation</li>
     * </ul>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Request configured for text-only extraction
     */
    public static VisionOcrRequest textOnly(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("en"), false, false);
    }

    /**
     * Create request for Hindi + English documents.
     *
     * @param imageBase64 Base64-encoded image data
     * @return Request configured for Hindi + English OCR
     */
    public static VisionOcrRequest hindiEnglish(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("hi", "en"), true, true);
    }
}
