package com.elioo.healthcare.medicalreport.application.port.out;

import com.elioo.healthcare.medicalreport.domain.TestResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Translation port (outbound port) for translating medical text.
 *
 * <p>This port defines the contract for translation capabilities needed by the
 * medical report processing domain. Implementations provide translation services
 * from various cloud providers (GCP Translation, AWS Translate, etc.).</p>
 *
 * <p><b>Hexagonal Architecture:</b></p>
 * <ul>
 *   <li><b>Domain</b> defines WHAT it needs (this interface)</li>
 *   <li><b>Infrastructure</b> implements HOW to provide it (TranslationAdapter)</li>
 *   <li>Domain never depends on infrastructure - only on this interface</li>
 * </ul>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *   <li>Translate Bangla medical text to English for AWS Comprehend Medical</li>
 *   <li>Store English-only data in database for consistency</li>
 *   <li>Preserve original language alongside translation for audit trail</li>
 *   <li>Handle mixed-language documents (Bangla + English)</li>
 * </ul>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code TranslationAdapter} - GCP Translation API adapter (elioo-gcp-translate)</li>
 * </ul>
 *
 * @see com.elioo.healthcare.medicalreport.adapter.out.gcp.TranslationAdapter
 * @see TestResult
 * @since 0.1.0
 */
public interface TranslationPort {

    /**
     * Translate text from source language to English.
     *
     * <p>This method translates arbitrary text (OCR raw text, notes, etc.) to English.
     * The source language can be explicitly provided or auto-detected.</p>
     *
     * <p><b>Behavior:</b></p>
     * <ul>
     *   <li>If sourceLanguage is null → auto-detect language</li>
     *   <li>If text is already English → skip translation (return as-is)</li>
     *   <li>If translation fails → return original text with warning</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * // Auto-detect source language
     * String translated = translationPort
     *     .translateToEnglish("রক্তের গ্লুকোজ: ৫.৫ mmol/L", null)
     *     .block();
     * // Result: "Blood Glucose: 5.5 mmol/L"
     *
     * // Explicit source language
     * String translated = translationPort
     *     .translateToEnglish("রক্তের গ্লুকোজ", "bn")
     *     .block();
     * // Result: "Blood Glucose"
     * }</pre>
     *
     * @param text Text to translate (Bangla, Hindi, or any supported language)
     * @param sourceLanguage Source language code (e.g., "bn", "hi") or null for auto-detect
     * @return Mono emitting translated English text
     */
    Mono<String> translateToEnglish(String text, String sourceLanguage);

    /**
     * Translate mixed-language text (e.g., Bangla + English).
     *
     * <p>This method intelligently handles mixed-language documents by:</p>
     * <ul>
     *   <li>Detecting English portions and preserving them as-is</li>
     *   <li>Translating only non-English portions to English</li>
     *   <li>Maintaining text structure and formatting</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * String mixedText = "রক্তের গ্লুকোজ: 5.5 mmol/L (Fasting)";
     * String translated = translationPort
     *     .translateMixedText(mixedText)
     *     .block();
     * // Result: "Blood Glucose: 5.5 mmol/L (Fasting)"
     * // English portions "5.5 mmol/L (Fasting)" preserved as-is
     * }</pre>
     *
     * @param text Mixed-language text (Bangla + English)
     * @return Mono emitting fully English text
     */
    Mono<String> translateMixedText(String text);

    /**
     * Translate individual TestResult fields (testName, referenceRange).
     *
     * <p>This method translates structured test data extracted from OCR:</p>
     * <ul>
     *   <li>testName: "রক্তের গ্লুকোজ" → "Blood Glucose"</li>
     *   <li>referenceRange: "স্বাভাবিক" → "Normal"</li>
     *   <li>testValue, unit: Numeric/English → Preserved as-is</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * TestResult banglaResult = TestResult.builder()
     *     .testName("রক্তের গ্লুকোজ")
     *     .testValue("5.5")
     *     .unit("mmol/L")
     *     .referenceRange("পুরুষ: 3.9-5.6")
     *     .build();
     *
     * TestResult englishResult = translationPort
     *     .translateTestResult(banglaResult)
     *     .block();
     * // testName: "Blood Glucose"
     * // testValue: "5.5" (preserved)
     * // unit: "mmol/L" (preserved)
     * // referenceRange: "Male: 3.9-5.6"
     * }</pre>
     *
     * @param testResult Test result with potentially Bangla fields
     * @return Mono emitting translated test result (English only)
     */
    Mono<TestResult> translateTestResult(TestResult testResult);

    /**
     * Batch translate multiple test results efficiently.
     *
     * <p>This method is optimized for translating multiple test results from
     * a single medical report. More efficient than calling {@link #translateTestResult(TestResult)}
     * multiple times.</p>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * List<TestResult> banglaResults = List.of(
     *     TestResult.builder().testName("রক্তের গ্লুকোজ").testValue("5.5").build(),
     *     TestResult.builder().testName("হিমোগ্লোবিন").testValue("13.2").build(),
     *     TestResult.builder().testName("সিরাম ক্রিয়েটিনিন").testValue("1.1").build()
     * );
     *
     * List<TestResult> englishResults = translationPort
     *     .translateTestResults(banglaResults)
     *     .collectList()
     *     .block();
     * // All test names translated to English
     * }</pre>
     *
     * @param testResults List of test results to translate
     * @return Flux emitting translated test results in the same order
     */
    Flux<TestResult> translateTestResults(List<TestResult> testResults);

    /**
     * Detect if text contains non-English content.
     *
     * <p>This utility method helps determine if translation is needed:</p>
     * <ul>
     *   <li>true → Text contains Bangla/Hindi/etc., translation needed</li>
     *   <li>false → Text is already English, skip translation</li>
     * </ul>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Skip translation API calls for English-only reports (cost optimization)</li>
     *   <li>Log language detection for analytics</li>
     *   <li>Route to appropriate processing pipeline</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * boolean needsTranslation = translationPort
     *     .containsNonEnglish("রক্তের গ্লুকোজ")
     *     .block(); // true
     *
     * boolean needsTranslation = translationPort
     *     .containsNonEnglish("Blood Glucose")
     *     .block(); // false
     * }</pre>
     *
     * @param text Text to check
     * @return Mono emitting true if text contains non-English content
     */
    Mono<Boolean> containsNonEnglish(String text);

    /**
     * Translation result wrapper with metadata.
     *
     * <p>This record captures translation metadata for audit and debugging:</p>
     * <ul>
     *   <li>originalText: Original text before translation</li>
     *   <li>translatedText: Translated text in target language</li>
     *   <li>sourceLanguage: Detected or provided source language</li>
     *   <li>wasTranslated: Whether translation actually occurred (false if already English)</li>
     * </ul>
     *
     * @param originalText Original text before translation
     * @param translatedText Translated text (or original if skipped)
     * @param sourceLanguage Source language code (e.g., "bn", "en")
     * @param wasTranslated Whether translation occurred (false if already English)
     */
    record TranslationResult(
            String originalText,
            String translatedText,
            String sourceLanguage,
            boolean wasTranslated
    ) {
        /**
         * Get effective text (translated or original).
         *
         * @return translated text if available, otherwise original
         */
        public String getEffectiveText() {
            return translatedText != null ? translatedText : originalText;
        }

        /**
         * Check if text was in English.
         *
         * @return true if source language was English
         */
        public boolean wasEnglish() {
            return "en".equalsIgnoreCase(sourceLanguage) ||
                   "eng".equalsIgnoreCase(sourceLanguage);
        }
    }
}
