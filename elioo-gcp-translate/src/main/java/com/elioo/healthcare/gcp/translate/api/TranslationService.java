package com.elioo.healthcare.gcp.translate.api;

import com.elioo.healthcare.gcp.translate.model.TranslationRequest;
import com.elioo.healthcare.gcp.translate.model.TranslationResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Translation service contract for Google Cloud Translation API.
 *
 * <p>This interface defines the contract for text translation services using
 * Google Cloud Translation API. Implementations provide translation capabilities
 * for converting text between 100+ supported languages.</p>
 *
 * <p>All operations are reactive and return {@link Mono} or {@link Flux} types
 * for non-blocking execution.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li><b>Text Translation:</b> Translate text between 100+ languages</li>
 *   <li><b>Language Detection:</b> Automatic source language detection</li>
 *   <li><b>Mixed-Language Support:</b> Handle mixed-language text (e.g., Bangla + English)</li>
 *   <li><b>Batch Translation:</b> Translate multiple texts efficiently</li>
 *   <li><b>Smart English Preservation:</b> Skip translation if already English</li>
 *   <li><b>Neural Machine Translation:</b> High-quality NMT models</li>
 * </ul>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code TranslationServiceImpl} - Google Cloud Translation implementation</li>
 * </ul>
 *
 * <p><b>Supported Languages (partial list):</b></p>
 * <ul>
 *   <li>bn - Bangla/Bengali</li>
 *   <li>en - English</li>
 *   <li>hi - Hindi</li>
 *   <li>es - Spanish</li>
 *   <li>fr - French</li>
 *   <li>de - German</li>
 *   <li>ja - Japanese</li>
 *   <li>ko - Korean</li>
 *   <li>zh - Chinese (Simplified)</li>
 *   <li>ar - Arabic</li>
 * </ul>
 *
 * @see TranslationRequest
 * @see TranslationResponse
 * @since 0.1.0
 */
public interface TranslationService {

    /**
     * Translate text from source language to target language.
     *
     * <p>This method translates text using Google Cloud Translation API's
     * Neural Machine Translation (NMT) models for high-quality results.</p>
     *
     * <p><b>Features:</b></p>
     * <ul>
     *   <li>Auto-detect source language if not provided</li>
     *   <li>Skip translation if text already in target language (when preserveEnglish=true)</li>
     *   <li>Support for 100+ language pairs</li>
     *   <li>HTML and plain text support</li>
     * </ul>
     *
     * <p><b>Performance Characteristics:</b></p>
     * <ul>
     *   <li>Latency: ~100-300ms for typical medical text (50-200 characters)</li>
     *   <li>Max Text Size: 30KB per request</li>
     *   <li>Billing: $20 per 1M characters (Cloud Translation Basic)</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * // Bangla to English
     * TranslationRequest request = TranslationRequest.banglaToEnglish("রক্তের গ্লুকোজ");
     * TranslationResponse response = translationService.translate(request).block();
     * System.out.println(response.getTranslatedText()); // "Blood Glucose"
     *
     * // Auto-detect source language
     * TranslationRequest request = TranslationRequest.autoDetect("রক্তের গ্লুকোজ", "en");
     * TranslationResponse response = translationService.translate(request).block();
     * System.out.println(response.getDetectedSourceLanguage()); // "bn"
     * }</pre>
     *
     * @param request Translation request with text and language parameters
     * @return Mono emitting translation response with translated text and metadata
     */
    Mono<TranslationResponse> translate(TranslationRequest request);

    /**
     * Translate multiple texts in a batch operation.
     *
     * <p>This method is more efficient than calling {@link #translate(TranslationRequest)}
     * multiple times because it batches API calls and reduces overhead.</p>
     *
     * <p><b>Benefits of Batch Translation:</b></p>
     * <ul>
     *   <li>Reduced API call overhead</li>
     *   <li>Lower latency for multiple texts</li>
     *   <li>More efficient quota usage</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * List<TranslationRequest> requests = List.of(
     *     TranslationRequest.banglaToEnglish("রক্তের গ্লুকোজ"),
     *     TranslationRequest.banglaToEnglish("হিমোগ্লোবিন"),
     *     TranslationRequest.banglaToEnglish("সিরাম ক্রিয়েটিনিন")
     * );
     *
     * List<TranslationResponse> responses = translationService
     *     .translateBatch(requests)
     *     .collectList()
     *     .block();
     * }</pre>
     *
     * @param requests List of translation requests
     * @return Flux emitting translation responses in the same order
     */
    Flux<TranslationResponse> translateBatch(List<TranslationRequest> requests);

    /**
     * Detect the language of the given text.
     *
     * <p>This method uses GCP's language detection to identify the source language
     * with high confidence. Useful for routing translation requests or validating
     * input language.</p>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * String language = translationService.detectLanguage("রক্তের গ্লুকোজ").block();
     * System.out.println(language); // "bn"
     *
     * String language = translationService.detectLanguage("Blood Glucose").block();
     * System.out.println(language); // "en"
     * }</pre>
     *
     * @param text Text to analyze
     * @return Mono emitting detected language code (e.g., "bn", "en", "hi")
     */
    Mono<String> detectLanguage(String text);

    /**
     * Check if text contains non-English content.
     *
     * <p>This is a utility method that combines language detection with English checking.
     * Useful for determining whether translation is needed.</p>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Skip translation for English-only text</li>
     *   <li>Route to translation pipeline only when needed</li>
     *   <li>Optimize API usage and costs</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * boolean needsTranslation = translationService
     *     .containsNonEnglish("রক্তের গ্লুকোজ")
     *     .block(); // true
     *
     * boolean needsTranslation = translationService
     *     .containsNonEnglish("Blood Glucose")
     *     .block(); // false
     * }</pre>
     *
     * @param text Text to check
     * @return Mono emitting true if text contains non-English content
     */
    Mono<Boolean> containsNonEnglish(String text);

    /**
     * Translate mixed-language text (e.g., Bangla + English).
     *
     * <p>This method intelligently handles mixed-language documents by:</p>
     * <ul>
     *   <li>Detecting language segments</li>
     *   <li>Translating only non-English portions</li>
     *   <li>Preserving English text as-is</li>
     *   <li>Maintaining original text structure</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * String mixedText = "রক্তের গ্লুকোজ: 5.5 mmol/L (Fasting)";
     * String translated = translationService
     *     .translateMixedText(mixedText, "bn", "en")
     *     .block();
     * // Result: "Blood Glucose: 5.5 mmol/L (Fasting)"
     * }</pre>
     *
     * @param text Mixed-language text
     * @param sourceLanguage Primary source language (e.g., "bn")
     * @param targetLanguage Target language (e.g., "en")
     * @return Mono emitting translated text with English portions preserved
     */
    Mono<String> translateMixedText(String text, String sourceLanguage, String targetLanguage);
}
