package com.elioo.healthcare.medicalreport.application.port.in;

import com.elioo.healthcare.medicalreport.domain.Language;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Inbound port (use case) for translating medical report content between languages.
 *
 * <p>Architecture: Inbound Port (Driving) in Hexagonal Architecture</p>
 *
 * <p>This use case handles:</p>
 * <ul>
 *   <li>Fetching translated content for a report (with caching)</li>
 *   <li>Translating chat responses in real-time</li>
 *   <li>Translating user messages to English for AI processing</li>
 * </ul>
 *
 * <p>Caching Strategy:</p>
 * <ul>
 *   <li>First request for translation: translate and cache in database</li>
 *   <li>Subsequent requests: return cached translation</li>
 *   <li>English requests: return original content (no translation needed)</li>
 * </ul>
 *
 * @see com.elioo.healthcare.medicalreport.domain.Language
 */
public interface ContentTranslationUseCase {

    /**
     * Get translated content for a specific report and result type.
     *
     * <p>Returns cached translation if available, otherwise translates
     * the English content and caches it for future requests.</p>
     *
     * <p>Supported result types:</p>
     * <ul>
     *   <li>CLINICAL_INSIGHTS</li>
     *   <li>RISK_ASSESSMENT</li>
     *   <li>RECOMMENDATIONS</li>
     *   <li>EDUCATIONAL_CONTENT</li>
     * </ul>
     *
     * @param reportId Report identifier
     * @param resultType Type of result (e.g., "CLINICAL_INSIGHTS")
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated content with metadata
     */
    Mono<TranslatedContent> getTranslatedContent(
        String reportId,
        String resultType,
        Language targetLanguage
    );

    /**
     * Get translated content for multiple result types at once.
     *
     * <p>More efficient than calling getTranslatedContent multiple times
     * as it can batch database operations.</p>
     *
     * @param reportId Report identifier
     * @param resultTypes List of result types to translate
     * @param targetLanguage Target language for translation
     * @return Mono emitting list of translated content
     */
    Mono<List<TranslatedContent>> getTranslatedContentBatch(
        String reportId,
        List<String> resultTypes,
        Language targetLanguage
    );

    /**
     * Translate a chat response to target language.
     *
     * <p>Chat responses are not cached as they are conversational
     * and unique to each interaction.</p>
     *
     * @param response AI-generated response in English
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated response text
     */
    Mono<String> translateChatResponse(String response, Language targetLanguage);

    /**
     * Translate user's chat message to English for AI processing.
     *
     * <p>Automatically detects if the message is in Bangla or another
     * non-English language and translates it to English.</p>
     *
     * @param message User's message (may be in any language)
     * @return Mono emitting English translation
     */
    Mono<String> translateUserMessageToEnglish(String message);

    /**
     * Invalidate cached translation for a specific report and result type.
     *
     * <p>Use this when the original content has been updated and
     * cached translations are no longer valid.</p>
     *
     * @param reportId Report identifier
     * @param resultType Type of result
     * @return Mono signaling completion
     */
    Mono<Void> invalidateTranslationCache(String reportId, String resultType);

    /**
     * Check if a translation is cached for the given parameters.
     *
     * @param reportId Report identifier
     * @param resultType Type of result
     * @param targetLanguage Target language
     * @return Mono emitting true if cached, false otherwise
     */
    Mono<Boolean> isTranslationCached(String reportId, String resultType, Language targetLanguage);

    /**
     * Record containing translated content and metadata.
     */
    record TranslatedContent(
        /**
         * Report identifier.
         */
        String reportId,

        /**
         * Type of result (e.g., "CLINICAL_INSIGHTS").
         */
        String resultType,

        /**
         * Translated content as JSON string.
         */
        String contentJson,

        /**
         * Language of the translated content.
         */
        Language language,

        /**
         * Whether this content was retrieved from cache.
         */
        boolean fromCache,

        /**
         * When the translation was created (null if from original).
         */
        LocalDateTime translatedAt
    ) {
        /**
         * Create a TranslatedContent for original English content.
         */
        public static TranslatedContent original(String reportId, String resultType, String contentJson) {
            return new TranslatedContent(reportId, resultType, contentJson, Language.EN, true, null);
        }

        /**
         * Create a TranslatedContent for newly translated content.
         */
        public static TranslatedContent translated(
            String reportId,
            String resultType,
            String contentJson,
            Language language
        ) {
            return new TranslatedContent(reportId, resultType, contentJson, language, false, LocalDateTime.now());
        }

        /**
         * Create a TranslatedContent for cached translation.
         */
        public static TranslatedContent cached(
            String reportId,
            String resultType,
            String contentJson,
            Language language,
            LocalDateTime translatedAt
        ) {
            return new TranslatedContent(reportId, resultType, contentJson, language, true, translatedAt);
        }
    }
}
