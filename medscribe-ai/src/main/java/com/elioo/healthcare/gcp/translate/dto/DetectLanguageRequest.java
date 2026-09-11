package com.elioo.healthcare.gcp.translate.dto;

/**
 * Request DTO for language detection.
 *
 * <p>Detects whether text contains non-English characters. Used to determine
 * if translation is needed before processing.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Detect if text needs translation
 * POST /api/gcp/translate/detect-language
 * {
 *   "text": "রক্তচাপ: 120/80 mmHg"
 * }
 *
 * // English text
 * POST /api/gcp/translate/detect-language
 * {
 *   "text": "Blood Pressure: 120/80 mmHg"
 * }
 * }</pre>
 *
 * @param text Text to analyze for language detection
 *
 * @since 1.0.0
 */
public record DetectLanguageRequest(String text) {
}
