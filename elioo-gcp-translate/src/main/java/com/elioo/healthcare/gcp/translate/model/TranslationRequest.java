package com.elioo.healthcare.gcp.translate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Translation request containing text and language parameters.
 *
 * <p>This class represents a request to translate text from one language to another
 * using Google Cloud Translation API.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li><b>Source Language:</b> Optional - auto-detect if null</li>
 *   <li><b>Target Language:</b> Required - target language code</li>
 *   <li><b>Text:</b> Required - text to translate</li>
 *   <li><b>Preserve English:</b> Optional - skip translation if text is already English</li>
 * </ul>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Auto-detect source language
 * TranslationRequest request = TranslationRequest.builder()
 *     .text("রক্তের গ্লুকোজ")
 *     .targetLanguage("en")
 *     .build();
 *
 * // Explicit source language
 * TranslationRequest request = TranslationRequest.banglaToEnglish("রক্তের গ্লুকোজ");
 * }</pre>
 *
 * @since 0.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationRequest {

    /**
     * Text to translate (required).
     */
    private String text;

    /**
     * Source language code (optional - auto-detect if null).
     * Examples: "bn" (Bangla), "hi" (Hindi), "es" (Spanish)
     */
    private String sourceLanguage;

    /**
     * Target language code (required).
     * Default: "en" (English)
     */
    @Builder.Default
    private String targetLanguage = "en";

    /**
     * Whether to preserve English text as-is without re-translating.
     * If true, text detected as English will not be sent to Translation API.
     * Default: true
     */
    @Builder.Default
    private Boolean preserveEnglish = true;

    /**
     * Model to use for translation (optional).
     * null = use default model
     * "nmt" = Neural Machine Translation (recommended)
     * "base" = Phrase-Based Machine Translation (legacy)
     */
    private String model;

    /**
     * MIME type of the text (optional).
     * "text/plain" (default) or "text/html"
     */
    @Builder.Default
    private String mimeType = "text/plain";

    // ==================== Factory Methods ====================

    /**
     * Create a translation request for Bangla to English.
     *
     * @param text Bangla text to translate
     * @return TranslationRequest configured for bn → en
     */
    public static TranslationRequest banglaToEnglish(String text) {
        return TranslationRequest.builder()
                .text(text)
                .sourceLanguage("bn")
                .targetLanguage("en")
                .preserveEnglish(true)
                .build();
    }

    /**
     * Create a translation request with auto-detected source language.
     *
     * @param text Text to translate
     * @param targetLanguage Target language code
     * @return TranslationRequest with auto-detect source
     */
    public static TranslationRequest autoDetect(String text, String targetLanguage) {
        return TranslationRequest.builder()
                .text(text)
                .sourceLanguage(null) // Auto-detect
                .targetLanguage(targetLanguage)
                .preserveEnglish(true)
                .build();
    }

    /**
     * Create a batch translation request for multiple texts.
     *
     * @param texts List of texts to translate
     * @param sourceLanguage Source language code (null for auto-detect)
     * @param targetLanguage Target language code
     * @return List of TranslationRequest objects
     */
    public static List<TranslationRequest> batch(
            List<String> texts,
            String sourceLanguage,
            String targetLanguage
    ) {
        return texts.stream()
                .map(text -> TranslationRequest.builder()
                        .text(text)
                        .sourceLanguage(sourceLanguage)
                        .targetLanguage(targetLanguage)
                        .preserveEnglish(true)
                        .build())
                .toList();
    }

    // ==================== Validation ====================

    /**
     * Validate that required fields are present.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return text != null && !text.isBlank() &&
               targetLanguage != null && !targetLanguage.isBlank();
    }

    /**
     * Check if this is an auto-detect request.
     *
     * @return true if sourceLanguage is null or blank
     */
    public boolean isAutoDetect() {
        return sourceLanguage == null || sourceLanguage.isBlank();
    }
}
