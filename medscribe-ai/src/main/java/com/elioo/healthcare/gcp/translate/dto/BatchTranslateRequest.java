package com.elioo.healthcare.gcp.translate.dto;

import java.util.List;

/**
 * Request DTO for batch translation of multiple texts.
 *
 * <p>Translates multiple text strings in a single request for improved efficiency.
 * Supports up to 100 texts per batch with total content up to 20,000 characters.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Medical terms translation
 * POST /api/gcp/translate/batch
 * {
 *   "texts": [
 *     "রক্তচাপ",
 *     "হিমোগ্লোবিন",
 *     "গ্লুকোজ",
 *     "ক্রিয়েটিনিন"
 *   ],
 *   "sourceLanguage": "bn",
 *   "targetLanguage": "en"
 * }
 *
 * // Factory method
 * var request = BatchTranslateRequest.banglaToEnglish(
 *     List.of("রক্তচাপ", "হিমোগ্লোবিন")
 * );
 * }</pre>
 *
 * @param texts          List of texts to translate (1-100 texts)
 * @param sourceLanguage Source language code (ISO 639-1) or null for auto-detect
 * @param targetLanguage Target language code (ISO 639-1, required)
 *
 * @since 1.0.0
 */
public record BatchTranslateRequest(
        List<String> texts,
        String sourceLanguage,
        String targetLanguage
) {
    /**
     * Create a batch request for Bangla to English translation.
     *
     * @param texts List of Bangla texts to translate
     * @return BatchTranslateRequest with sourceLanguage="bn", targetLanguage="en"
     */
    public static BatchTranslateRequest banglaToEnglish(List<String> texts) {
        return new BatchTranslateRequest(texts, "bn", "en");
    }

    /**
     * Create a batch request for English to Bangla translation.
     *
     * @param texts List of English texts to translate
     * @return BatchTranslateRequest with sourceLanguage="en", targetLanguage="bn"
     */
    public static BatchTranslateRequest englishToBangla(List<String> texts) {
        return new BatchTranslateRequest(texts, "en", "bn");
    }

    /**
     * Create a batch request with auto-detection of source language.
     *
     * @param texts          List of texts to translate
     * @param targetLanguage Target language code
     * @return BatchTranslateRequest with sourceLanguage=null
     */
    public static BatchTranslateRequest autoDetect(List<String> texts, String targetLanguage) {
        return new BatchTranslateRequest(texts, null, targetLanguage);
    }
}
