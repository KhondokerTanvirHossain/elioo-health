package com.elioo.healthcare.medicalreport.application.port.out;

import com.elioo.healthcare.medicalreport.domain.Language;
import reactor.core.publisher.Mono;

/**
 * Outbound port for translating AI-generated clinical content between languages.
 *
 * <p>Architecture: Outbound Port (Driven) in Hexagonal Architecture</p>
 *
 * <p>This port handles translation of complex structured content types:</p>
 * <ul>
 *   <li>Clinical insights (summary, findings, recommendations)</li>
 *   <li>Risk assessments (explanations, factors, prevention strategies)</li>
 *   <li>Recommendations (recommendations, rationale, timeframes)</li>
 *   <li>Educational content (title, content, FAQs, resources)</li>
 *   <li>Chat responses (free-text AI responses)</li>
 * </ul>
 *
 * <p>Translation Strategy:</p>
 * <ul>
 *   <li>Content is generated in English by Bedrock/Claude</li>
 *   <li>English content is stored as the canonical version</li>
 *   <li>Translation to other languages (e.g., Bangla) happens on-demand</li>
 *   <li>Translated content is cached in the database</li>
 * </ul>
 *
 * @see Language
 */
public interface ContentTranslationPort {

    /**
     * Translate clinical insight result JSON to target language.
     *
     * <p>Translates text fields while preserving JSON structure and
     * non-translatable fields like severity levels, scores, and codes.</p>
     *
     * @param clinicalInsightsJson JSON string containing clinical insights
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated JSON string
     */
    Mono<String> translateClinicalInsights(String clinicalInsightsJson, Language targetLanguage);

    /**
     * Translate risk assessment result JSON to target language.
     *
     * <p>Translates explanation text and risk factor descriptions while
     * preserving risk scores and levels.</p>
     *
     * @param riskAssessmentJson JSON string containing risk assessment
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated JSON string
     */
    Mono<String> translateRiskAssessment(String riskAssessmentJson, Language targetLanguage);

    /**
     * Translate recommendations result JSON to target language.
     *
     * <p>Translates recommendation text, rationale, and timeframes while
     * preserving priority levels and categories.</p>
     *
     * @param recommendationsJson JSON string containing recommendations
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated JSON string
     */
    Mono<String> translateRecommendations(String recommendationsJson, Language targetLanguage);

    /**
     * Translate educational content result JSON to target language.
     *
     * <p>Translates title, content, FAQs, key takeaways, and resources.</p>
     *
     * @param educationalContentJson JSON string containing educational content
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated JSON string
     */
    Mono<String> translateEducationalContent(String educationalContentJson, Language targetLanguage);

    /**
     * Translate a chat response to target language.
     *
     * <p>Translates free-text AI response. Unlike other content types,
     * chat responses are not cached as they are conversational.</p>
     *
     * @param response AI-generated response text in English
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated response text
     */
    Mono<String> translateChatResponse(String response, Language targetLanguage);

    /**
     * Translate user's chat message to English for AI processing.
     *
     * <p>Automatically detects if the message is in a non-English language
     * (e.g., Bangla) and translates it to English so Claude can process it.</p>
     *
     * @param message User's message (may be in any supported language)
     * @return Mono emitting English translation, or original if already English
     */
    Mono<String> translateUserMessageToEnglish(String message);

    /**
     * Generic translation method for any JSON content.
     *
     * <p>Identifies translatable text fields in the JSON and translates them
     * while preserving structure and non-translatable values.</p>
     *
     * @param contentJson JSON string to translate
     * @param contentType Type hint for context-aware translation (e.g., "CLINICAL_INSIGHTS")
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated JSON string
     */
    Mono<String> translateContent(String contentJson, String contentType, Language targetLanguage);

    /**
     * Translate a simple text string from English to target language.
     *
     * @param text English text to translate
     * @param targetLanguage Target language for translation
     * @return Mono emitting translated text
     */
    Mono<String> translateText(String text, Language targetLanguage);
}
