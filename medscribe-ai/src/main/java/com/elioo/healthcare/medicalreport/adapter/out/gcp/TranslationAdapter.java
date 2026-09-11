package com.elioo.healthcare.medicalreport.adapter.out.gcp;

import com.elioo.healthcare.gcp.translate.api.TranslationService;
import com.elioo.healthcare.gcp.translate.model.TranslationRequest;
import com.elioo.healthcare.gcp.translate.model.TranslationResponse;
import com.elioo.healthcare.medicalreport.application.port.out.TranslationPort;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Google Cloud Translation implementation of TranslationPort.
 *
 * <p>This adapter integrates the elioo-gcp-translate library with medscribe-ai's
 * medical report processing workflow. It translates Bangla medical text to English
 * for AWS Comprehend Medical processing and database storage.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>✅ <b>Bangla to English translation</b> - Primary use case</li>
 *   <li>✅ <b>Mixed-language support</b> - Preserves English portions</li>
 *   <li>✅ <b>Test result translation</b> - Translates testName, referenceRange</li>
 *   <li>✅ <b>Error handling</b> - Returns original text on failure (lenient mode)</li>
 *   <li>✅ <b>Batch optimization</b> - Efficient batch translation</li>
 * </ul>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements the business-defined port interface ({@link TranslationPort})</li>
 *   <li>Delegates to {@link TranslationService} from elioo-gcp-translate library</li>
 *   <li>Maps between medscribe-ai domain objects and library DTOs</li>
 *   <li>Acts as Anti-Corruption Layer between domain and library</li>
 * </ul>
 *
 * <p><b>Activation:</b></p>
 * <pre>
 * # application-gcp.properties
 * gcp.enabled=true
 * gcp.translate.enabled=true
 * gcp.translate.default-source-language=bn
 * gcp.translate.target-language=en
 * gcp.translate.preserve-english=true
 * </pre>
 *
 * <p><b>Error Handling Strategy:</b></p>
 * <ul>
 *   <li>Translation fails → Return original text + log warning</li>
 *   <li>Text already English → Skip translation (optimization)</li>
 *   <li>Language detection fails → Continue with warning</li>
 * </ul>
 *
 * @see TranslationPort
 * @see TranslationService
 * @since 0.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gcp.translate.enabled", havingValue = "true", matchIfMissing = false)
public class TranslationAdapter implements TranslationPort {

    private final TranslationService translationService;

    @Override
    public Mono<String> translateToEnglish(String text, String sourceLanguage) {
        log.info("[GCP Translate] translateToEnglish called: textLength={}, sourceLanguage={}",
                text != null ? text.length() : 0, sourceLanguage);

        if (text == null || text.isBlank()) {
            log.warn("[GCP Translate] Empty text provided for translation, skipping");
            return Mono.just("");
        }

        // Log sample of input text (first 100 chars)
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
        log.info("[GCP Translate] Input text preview: \"{}\"", textPreview);

        // Build translation request
        TranslationRequest request = sourceLanguage != null && !sourceLanguage.isBlank()
                ? TranslationRequest.builder()
                        .text(text)
                        .sourceLanguage(sourceLanguage)
                        .targetLanguage("en")
                        .preserveEnglish(true)
                        .build()
                : TranslationRequest.autoDetect(text, "en");

        log.info("[GCP Translate] Calling GCP Translation API: source={}, target=en, preserveEnglish=true",
                sourceLanguage != null ? sourceLanguage : "auto-detect");

        // Execute translation with error fallback
        return translationService.translate(request)
                .map(TranslationResponse::getTranslatedText)
                .doOnSuccess(translated -> {
                    String translatedPreview = translated.length() > 100 ? translated.substring(0, 100) + "..." : translated;
                    log.info("[GCP Translate] ✅ Translation successful: {} chars → {} chars", text.length(), translated.length());
                    log.info("[GCP Translate] Output text preview: \"{}\"", translatedPreview);
                })
                .onErrorResume(error -> {
                    log.error("[GCP Translate] ❌ Translation failed: {}", error.getMessage(), error);
                    return Mono.just(text); // Lenient: return original on failure
                });
    }

    @Override
    public Mono<String> translateMixedText(String text) {
        log.info("[GCP Translate] translateMixedText called: textLength={}", text != null ? text.length() : 0);

        if (text == null || text.isBlank()) {
            log.warn("[GCP Translate] Empty mixed text provided for translation, skipping");
            return Mono.just("");
        }

        // Log sample of input text (first 150 chars)
        String textPreview = text.length() > 150 ? text.substring(0, 150) + "..." : text;
        log.info("[GCP Translate] Mixed text input preview: \"{}\"", textPreview);

        log.info("[GCP Translate] Calling GCP Translation API for mixed text: source=bn, target=en");

        // Use translateMixedText from service (preserves English portions)
        return translationService.translateMixedText(text, "bn", "en")
                .doOnSuccess(translated -> {
                    String translatedPreview = translated.length() > 150 ? translated.substring(0, 150) + "..." : translated;
                    log.info("[GCP Translate] ✅ Mixed text translation successful: {} chars → {} chars",
                            text.length(), translated.length());
                    log.info("[GCP Translate] Mixed text output preview: \"{}\"", translatedPreview);
                })
                .onErrorResume(error -> {
                    log.error("[GCP Translate] ❌ Mixed text translation failed: {}", error.getMessage(), error);
                    return Mono.just(text); // Lenient: return original on failure
                });
    }

    @Override
    public Mono<TestResult> translateTestResult(TestResult testResult) {
        log.debug("Translating test result: {}", testResult.getTestName());

        if (testResult == null) {
            log.warn("Null test result provided for translation");
            return Mono.empty();
        }

        // Translate testName and referenceRange (testValue and unit are typically numeric/English)
        Mono<String> translatedTestName = testResult.getTestName() != null
                ? translateToEnglish(testResult.getTestName(), "bn")
                : Mono.just("");

        Mono<String> translatedRefRange = testResult.getReferenceRange() != null
                ? translateToEnglish(testResult.getReferenceRange(), "bn")
                : Mono.just(null);

        // Combine translations and build new TestResult
        return Mono.zip(translatedTestName, translatedRefRange)
                .map(tuple -> TestResult.builder()
                        .testName(tuple.getT1())
                        .testValue(testResult.getTestValue()) // Preserve numeric value
                        .unit(testResult.getUnit()) // Preserve unit (typically English)
                        .referenceRange(tuple.getT2())
                        .status(testResult.getStatus()) // Preserve status
                        .confidence(testResult.getConfidence()) // Preserve confidence
                        .build())
                .doOnSuccess(translated -> log.debug("Test result translated: {} → {}",
                        testResult.getTestName(), translated.getTestName()))
                .onErrorResume(error -> {
                    log.warn("Test result translation failed, returning original. Error: {}", error.getMessage());
                    return Mono.just(testResult); // Lenient: return original on failure
                });
    }

    @Override
    public Flux<TestResult> translateTestResults(List<TestResult> testResults) {
        log.info("Batch translating {} test results", testResults != null ? testResults.size() : 0);

        if (testResults == null || testResults.isEmpty()) {
            log.warn("Empty test results list provided for translation");
            return Flux.empty();
        }

        // Batch translate all test results
        return Flux.fromIterable(testResults)
                .flatMap(this::translateTestResult)
                .doOnComplete(() -> log.info("Batch translation completed: {} results", testResults.size()))
                .onErrorResume(error -> {
                    log.error("Batch translation failed: {}", error.getMessage(), error);
                    // Lenient: return original results on batch failure
                    return Flux.fromIterable(testResults);
                });
    }

    @Override
    public Mono<Boolean> containsNonEnglish(String text) {
        log.info("[GCP Translate] containsNonEnglish check: textLength={}", text != null ? text.length() : 0);

        if (text == null || text.isBlank()) {
            log.info("[GCP Translate] Empty text, assuming English (no translation needed)");
            return Mono.just(false);
        }

        // Log sample of text being checked
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
        log.info("[GCP Translate] Checking language for: \"{}\"", textPreview);

        // STEP 1: Character-based check (fast, no API call required)
        if (containsBanglaCharacters(text)) {
            log.info("[GCP Translate] 🔤 Bangla Unicode characters detected - translation REQUIRED");
            return Mono.just(true);
        }

        // STEP 2: GCP API detection (for other languages and verification)
        return translationService.containsNonEnglish(text)
                .doOnSuccess(hasNonEnglish -> {
                    if (hasNonEnglish) {
                        log.info("[GCP Translate] 🌐 Non-English content DETECTED via API - translation required");
                    } else {
                        log.info("[GCP Translate] 🇬🇧 Text is English (API confirmed) - skipping translation");
                    }
                })
                .onErrorResume(error -> {
                    log.error("[GCP Translate] ⚠️ API language detection failed, falling back to character analysis: {}",
                              error.getMessage());

                    // Fallback: if text has significant non-ASCII characters, assume non-English
                    long nonAsciiCount = text.chars().filter(ch -> ch > 127).count();
                    boolean hasNonAscii = nonAsciiCount > (text.length() * 0.2); // 20% threshold

                    log.info("[GCP Translate] Fallback analysis: {} non-ASCII chars out of {} total ({:.1f}%)",
                             nonAsciiCount, text.length(), (nonAsciiCount * 100.0 / text.length()));
                    log.info("[GCP Translate] Fallback result: hasNonAscii={} - translation {}",
                             hasNonAscii, hasNonAscii ? "REQUIRED" : "SKIPPED");

                    return Mono.just(hasNonAscii);
                });
    }

    // ==================== Helper Methods ====================

    /**
     * Check if text contains Bangla Unicode characters.
     *
     * <p>This method checks for Bangla script characters including:</p>
     * <ul>
     *   <li>Bangla consonants: ক-হ (U+0995 to U+09B9)</li>
     *   <li>Bangla vowels and diacritics: া-ৌ (U+09BE to U+09CC)</li>
     *   <li>Bangla digits: ০-৯ (U+09E6 to U+09EF)</li>
     * </ul>
     *
     * @param text Text to check
     * @return true if ANY Bangla Unicode characters are found
     */
    private boolean containsBanglaCharacters(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        // Check for Bangla Unicode characters
        for (char c : text.toCharArray()) {
            // Bangla consonants: ক-হ (U+0995 to U+09B9)
            // Bangla vowels/diacritics: া-ৌ (U+09BE to U+09CC)
            // Bangla digits: ০-৯ (U+09E6 to U+09EF)
            if ((c >= '\u0995' && c <= '\u09B9') ||  // Consonants
                (c >= '\u09BE' && c <= '\u09CC') ||  // Vowels/diacritics
                (c >= '\u09E6' && c <= '\u09EF')) {  // Digits
                return true;
            }
        }

        return false;
    }

    /**
     * Check if text is likely numeric/English (skip translation).
     *
     * @param text Text to check
     * @return true if text appears to be numeric or English
     */
    private boolean isNumericOrEnglish(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }

        // Simple heuristic: if mostly ASCII characters, likely English or numeric
        long nonAsciiCount = text.chars().filter(ch -> ch > 127).count();
        double nonAsciiRatio = (double) nonAsciiCount / text.length();

        // If less than 10% non-ASCII, consider it English/numeric
        return nonAsciiRatio < 0.1;
    }
}
