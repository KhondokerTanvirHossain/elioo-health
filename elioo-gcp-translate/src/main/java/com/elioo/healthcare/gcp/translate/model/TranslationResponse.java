package com.elioo.healthcare.gcp.translate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Translation response containing translated text and metadata.
 *
 * <p>This class represents the result of a translation operation using
 * Google Cloud Translation API.</p>
 *
 * <p><b>Response Fields:</b></p>
 * <ul>
 *   <li><b>translatedText:</b> The translated text in target language</li>
 *   <li><b>detectedSourceLanguage:</b> Auto-detected source language (if auto-detect used)</li>
 *   <li><b>sourceLanguage:</b> Original source language (from request or detected)</li>
 *   <li><b>targetLanguage:</b> Target language (from request)</li>
 *   <li><b>wasTranslated:</b> Whether translation actually occurred</li>
 *   <li><b>model:</b> Translation model used</li>
 * </ul>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * TranslationResponse response = translationService
 *     .translate(TranslationRequest.banglaToEnglish("রক্তের গ্লুকোজ"))
 *     .block();
 *
 * System.out.println(response.getTranslatedText()); // "Blood Glucose"
 * System.out.println(response.getDetectedSourceLanguage()); // "bn"
 * }</pre>
 *
 * @since 0.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationResponse {

    /**
     * The translated text in target language.
     */
    private String translatedText;

    /**
     * Original text (from request).
     */
    private String originalText;

    /**
     * Detected source language code (if auto-detect was used).
     * null if source language was explicitly provided.
     */
    private String detectedSourceLanguage;

    /**
     * Source language code (from request or detected).
     */
    private String sourceLanguage;

    /**
     * Target language code (from request).
     */
    private String targetLanguage;

    /**
     * Whether the text was actually translated.
     * False if text was already in target language and preserveEnglish=true.
     */
    @Builder.Default
    private boolean wasTranslated = true;

    /**
     * Translation model used.
     * "nmt" = Neural Machine Translation
     * "base" = Phrase-Based Machine Translation
     */
    private String model;

    /**
     * Optional glossary used (reserved for future use).
     */
    private String glossaryConfig;

    // ==================== Helper Methods ====================

    /**
     * Check if source language was auto-detected.
     *
     * @return true if language was auto-detected
     */
    public boolean wasAutoDetected() {
        return detectedSourceLanguage != null && !detectedSourceLanguage.isBlank();
    }

    /**
     * Check if text is in English.
     *
     * @return true if source language is English
     */
    public boolean isEnglish() {
        return "en".equalsIgnoreCase(sourceLanguage) ||
               "eng".equalsIgnoreCase(sourceLanguage);
    }

    /**
     * Check if text is in Bangla.
     *
     * @return true if source language is Bangla
     */
    public boolean isBangla() {
        return "bn".equalsIgnoreCase(sourceLanguage) ||
               "ben".equalsIgnoreCase(sourceLanguage);
    }

    /**
     * Check if translation was skipped (already in target language).
     *
     * @return true if translation was skipped
     */
    public boolean wasSkipped() {
        return !wasTranslated;
    }

    /**
     * Get the effective text (translated or original).
     *
     * @return translated text if available, otherwise original
     */
    public String getEffectiveText() {
        return translatedText != null ? translatedText : originalText;
    }

    // ==================== Factory Methods ====================

    /**
     * Create a response for skipped translation (text already in target language).
     *
     * @param originalText Original text
     * @param sourceLanguage Source language
     * @param targetLanguage Target language
     * @return TranslationResponse with wasTranslated=false
     */
    public static TranslationResponse skipped(
            String originalText,
            String sourceLanguage,
            String targetLanguage
    ) {
        return TranslationResponse.builder()
                .originalText(originalText)
                .translatedText(originalText)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .wasTranslated(false)
                .build();
    }

    /**
     * Create a response for successful translation.
     *
     * @param originalText Original text
     * @param translatedText Translated text
     * @param sourceLanguage Source language
     * @param targetLanguage Target language
     * @return TranslationResponse with wasTranslated=true
     */
    public static TranslationResponse success(
            String originalText,
            String translatedText,
            String sourceLanguage,
            String targetLanguage
    ) {
        return TranslationResponse.builder()
                .originalText(originalText)
                .translatedText(translatedText)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .wasTranslated(true)
                .build();
    }

    /**
     * Create a response with auto-detected language.
     *
     * @param originalText Original text
     * @param translatedText Translated text
     * @param detectedLanguage Detected source language
     * @param targetLanguage Target language
     * @return TranslationResponse with detected language
     */
    public static TranslationResponse autoDetected(
            String originalText,
            String translatedText,
            String detectedLanguage,
            String targetLanguage
    ) {
        return TranslationResponse.builder()
                .originalText(originalText)
                .translatedText(translatedText)
                .detectedSourceLanguage(detectedLanguage)
                .sourceLanguage(detectedLanguage)
                .targetLanguage(targetLanguage)
                .wasTranslated(true)
                .build();
    }
}
