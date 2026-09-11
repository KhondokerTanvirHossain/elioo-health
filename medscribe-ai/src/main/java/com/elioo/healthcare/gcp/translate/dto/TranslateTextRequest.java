package com.elioo.healthcare.gcp.translate.dto;

/**
 * Request DTO for single text translation using Google Cloud Translation API.
 *
 * <p>Translates text from one language to another using Neural Machine Translation (NMT).
 * Supports auto-detection of source language or explicit specification.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Explicit source and target languages
 * POST /api/gcp/translate/translate
 * {
 *   "text": "রক্তচাপ",
 *   "sourceLanguage": "bn",
 *   "targetLanguage": "en"
 * }
 *
 * // Auto-detect source language
 * POST /api/gcp/translate/translate
 * {
 *   "text": "রক্তচাপ",
 *   "sourceLanguage": null,
 *   "targetLanguage": "en"
 * }
 *
 * // Factory methods
 * var request1 = TranslateTextRequest.banglaToEnglish("রক্তচাপ");
 * var request2 = TranslateTextRequest.autoDetect("Unknown language", "en");
 * }</pre>
 *
 * @param text           The text to translate (up to 20,000 characters)
 * @param sourceLanguage Source language code (ISO 639-1) or null for auto-detect
 * @param targetLanguage Target language code (ISO 639-1, required)
 *
 * @since 1.0.0
 */
public record TranslateTextRequest(
        String text,
        String sourceLanguage,
        String targetLanguage
) {
    /**
     * Create a request for Bangla to English translation.
     *
     * @param text Bangla text to translate
     * @return TranslateTextRequest with sourceLanguage="bn", targetLanguage="en"
     */
    public static TranslateTextRequest banglaToEnglish(String text) {
        return new TranslateTextRequest(text, "bn", "en");
    }

    /**
     * Create a request for English to Bangla translation.
     *
     * @param text English text to translate
     * @return TranslateTextRequest with sourceLanguage="en", targetLanguage="bn"
     */
    public static TranslateTextRequest englishToBangla(String text) {
        return new TranslateTextRequest(text, "en", "bn");
    }

    /**
     * Create a request with auto-detection of source language.
     *
     * @param text           Text to translate
     * @param targetLanguage Target language code
     * @return TranslateTextRequest with sourceLanguage=null
     */
    public static TranslateTextRequest autoDetect(String text, String targetLanguage) {
        return new TranslateTextRequest(text, null, targetLanguage);
    }
}
