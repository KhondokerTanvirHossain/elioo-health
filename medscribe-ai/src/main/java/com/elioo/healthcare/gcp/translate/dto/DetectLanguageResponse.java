package com.elioo.healthcare.gcp.translate.dto;

/**
 * Response DTO for language detection.
 *
 * <p>Contains language detection results indicating whether text is English or contains
 * non-English characters that require translation.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * // Non-English detected (Bangla)
 * {
 *   "detectedLanguage": "non-en",
 *   "languageName": "Non-English",
 *   "confidence": 1.0,
 *   "isEnglish": false
 * }
 *
 * // English detected
 * {
 *   "detectedLanguage": "en",
 *   "languageName": "English",
 *   "confidence": 1.0,
 *   "isEnglish": true
 * }
 * }</pre>
 *
 * @param detectedLanguage Language code ("en" or "non-en")
 * @param languageName     Human-readable language name
 * @param confidence       Detection confidence (always 1.0)
 * @param isEnglish        Whether text is in English
 *
 * @since 1.0.0
 */
public record DetectLanguageResponse(
        String detectedLanguage,
        String languageName,
        double confidence,
        boolean isEnglish
) {
}
