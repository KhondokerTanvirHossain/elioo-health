package com.elioo.healthcare.gcp.translate.dto;

import java.util.List;

/**
 * Response DTO for batch translation of multiple texts.
 *
 * <p>Contains translation results for each text in the batch, including success/failure
 * status and aggregate statistics.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * {
 *   "translations": [
 *     {
 *       "originalText": "রক্তচাপ",
 *       "translatedText": "Blood Pressure",
 *       "success": true,
 *       "error": null
 *     },
 *     {
 *       "originalText": "হিমোগ্লোবিন",
 *       "translatedText": "Hemoglobin",
 *       "success": true,
 *       "error": null
 *     },
 *     {
 *       "originalText": "Invalid\u0000Text",
 *       "translatedText": "Invalid\u0000Text",
 *       "success": false,
 *       "error": "Invalid characters in text"
 *     }
 *   ],
 *   "totalCount": 3,
 *   "successCount": 2,
 *   "failureCount": 1
 * }
 * }</pre>
 *
 * @param translations  List of translation results
 * @param totalCount    Total number of texts in batch
 * @param successCount  Number of successful translations
 * @param failureCount  Number of failed translations
 *
 * @since 1.0.0
 */
public record BatchTranslateResponse(
        List<TranslationResult> translations,
        int totalCount,
        int successCount,
        int failureCount
) {
    /**
     * Individual translation result within a batch.
     *
     * @param originalText   Original text before translation
     * @param translatedText Translated text (or original if failed)
     * @param success        Whether translation succeeded
     * @param error          Error message if failed, null if successful
     */
    public record TranslationResult(
            String originalText,
            String translatedText,
            boolean success,
            String error
    ) {
    }
}
