package com.elioo.healthcare.gcp.translate.service;

import com.elioo.healthcare.gcp.translate.api.TranslationService;
import com.elioo.healthcare.gcp.translate.config.TranslateProperties;
import com.elioo.healthcare.gcp.translate.model.TranslationRequest;
import com.elioo.healthcare.gcp.translate.model.TranslationResponse;
import com.google.cloud.translate.v3.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Google Cloud Translation API implementation.
 *
 * <p>This implementation uses Cloud Translation API v3 with Neural Machine Translation
 * for high-quality translation between 100+ languages.</p>
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Reactive: Wraps GCP synchronous client in Mono/Flux for non-blocking operations</li>
 *   <li>Auto-configuration: Automatically configured via Spring Boot</li>
 *   <li>Error Handling: Comprehensive error handling with fallbacks</li>
 *   <li>Language Detection: Built-in language detection for auto-detect scenarios</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranslationServiceImpl implements TranslationService {

    private final TranslationServiceClient translationClient;
    private final TranslateProperties properties;

    @Override
    public Mono<TranslationResponse> translate(TranslationRequest request) {
        return Mono.fromCallable(() -> {
            log.info("[GCP Translation API] >>> Request: textLength={}, source={}, target={}, preserveEnglish={}",
                    request.getText().length(),
                    request.getSourceLanguage(),
                    request.getTargetLanguage(),
                    request.getPreserveEnglish());

            // Validate request
            if (!request.isValid()) {
                log.error("[GCP Translation API] Invalid request: text and targetLanguage are required");
                throw new IllegalArgumentException("Invalid translation request: text and targetLanguage are required");
            }

            // Check if we should skip translation (text already in target language)
            if (request.getPreserveEnglish() != null && request.getPreserveEnglish()) {
                log.info("[GCP Translation API] Checking if text is already English...");
                String detectedLang = detectLanguageSync(request.getText());
                if ("en".equalsIgnoreCase(detectedLang) &&
                    "en".equalsIgnoreCase(request.getTargetLanguage())) {
                    log.info("[GCP Translation API] ⏭️ SKIPPED - text already in English (detected: {})", detectedLang);
                    return TranslationResponse.skipped(
                            request.getText(),
                            "en",
                            request.getTargetLanguage()
                    );
                }
                log.info("[GCP Translation API] Detected language: {} - proceeding with translation", detectedLang);
            }

            // Build parent location
            LocationName parent = LocationName.of(properties.getProjectId(), properties.getLocation() != null ? properties.getLocation() : "global");
            log.info("[GCP Translation API] Project: {}, Location: {}", properties.getLocation() != null ? properties.getLocation() : "global", properties.getProjectId());

            // Build translation request
            TranslateTextRequest.Builder requestBuilder = TranslateTextRequest.newBuilder()
                    .setParent(parent.toString())
                    .addContents(request.getText())
                    .setTargetLanguageCode(request.getTargetLanguage())
                    .setMimeType(request.getMimeType());

            // Add source language if provided
            if (!request.isAutoDetect()) {
                requestBuilder.setSourceLanguageCode(request.getSourceLanguage());
                log.info("[GCP Translation API] Source language explicitly set: {}", request.getSourceLanguage());
            } else {
                log.info("[GCP Translation API] Using auto-detect for source language");
            }

            // Add model if specified
            if (request.getModel() != null && !request.getModel().isBlank()) {
                String modelPath = String.format(
                        "projects/%s/locations/global/models/%s",
                        properties.getProjectId(),
                        request.getModel()
                );
                requestBuilder.setModel(modelPath);
                log.info("[GCP Translation API] Using custom model: {}", modelPath);
            }

            // Execute translation
            log.info("[GCP Translation API] 🚀 Calling translateText API...");
            long startTime = System.currentTimeMillis();
            TranslateTextResponse response = translationClient.translateText(requestBuilder.build());
            long duration = System.currentTimeMillis() - startTime;

            // Extract result
            com.google.cloud.translate.v3.Translation translation = response.getTranslations(0);
            String translatedText = translation.getTranslatedText();
            String detectedLanguage = translation.getDetectedLanguageCode();

            log.info("[GCP Translation API] <<< Response: outputLength={}, detectedLang={}, model={}, duration={}ms",
                    translatedText.length(),
                    detectedLanguage,
                    translation.getModel(),
                    duration);

            // Build response
            if (request.isAutoDetect() && detectedLanguage != null && !detectedLanguage.isBlank()) {
                log.info("[GCP Translation API] ✅ Translation complete (auto-detected: {} → {})",
                        detectedLanguage, request.getTargetLanguage());
                return TranslationResponse.autoDetected(
                        request.getText(),
                        translatedText,
                        detectedLanguage,
                        request.getTargetLanguage()
                );
            } else {
                log.info("[GCP Translation API] ✅ Translation complete ({} → {})",
                        request.getSourceLanguage(), request.getTargetLanguage());
                return TranslationResponse.success(
                        request.getText(),
                        translatedText,
                        request.getSourceLanguage(),
                        request.getTargetLanguage()
                );
            }
        }).doOnError(e -> log.error("[GCP Translation API] ❌ Translation failed: {}", e.getMessage(), e));
    }

    @Override
    public Flux<TranslationResponse> translateBatch(List<TranslationRequest> requests) {
        return Flux.fromIterable(requests)
                .flatMap(this::translate)
                .doOnComplete(() -> log.debug("Batch translation completed: {} requests", requests.size()));
    }

    @Override
    public Mono<String> detectLanguage(String text) {
        log.info("[GCP Translation API] detectLanguage called: textLength={}", text != null ? text.length() : 0);
        return Mono.fromCallable(() -> detectLanguageSync(text))
                .doOnSuccess(lang -> log.info("[GCP Translation API] detectLanguage result: {}", lang))
                .doOnError(e -> log.error("[GCP Translation API] detectLanguage failed: {}", e.getMessage(), e));
    }

    @Override
    public Mono<Boolean> containsNonEnglish(String text) {
        log.info("[GCP Translation API] containsNonEnglish check starting...");
        return detectLanguage(text)
                .map(lang -> {
                    boolean isNonEnglish = !"en".equalsIgnoreCase(lang) && !"eng".equalsIgnoreCase(lang);
                    log.info("[GCP Translation API] containsNonEnglish: detected={}, isNonEnglish={}", lang, isNonEnglish);
                    return isNonEnglish;
                })
                .onErrorReturn(false); // Default to false on error
    }

    @Override
    public Mono<String> translateMixedText(String text, String sourceLanguage, String targetLanguage) {
        log.info("[GCP Translation API] translateMixedText: length={}, source={}, target={}",
                text.length(), sourceLanguage, targetLanguage);

        // Simple approach: translate entire text block
        // GCP Translation API preserves English portions naturally
        TranslationRequest request = TranslationRequest.builder()
                .text(text)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .preserveEnglish(true)
                .build();

        log.info("[GCP Translation API] translateMixedText: calling translate()...");
        return translate(request)
                .map(response -> {
                    String result = response != null ? response.getTranslatedText() : text;
                    log.info("[GCP Translation API] translateMixedText: completed, outputLength={}", result.length());
                    return result;
                })
                .doOnError(e -> {
                    log.error("[GCP Translation API] translateMixedText failed: {}", e.getMessage(), e);
                })
                .onErrorReturn(text); // Return original text on error
    }

    // ==================== Private Helper Methods ====================

    /**
     * Synchronous language detection helper.
     *
     * @param text Text to detect
     * @return Detected language code
     */
    private String detectLanguageSync(String text) {
        try {
            LocationName parent = LocationName.of(properties.getProjectId(), properties.getLocation() != null ? properties.getLocation() : "global");

            // Log text sample for debugging
            String textSample = text.length() > 100 ? text.substring(0, 100) + "..." : text;
            log.info("[GCP Translation API] 🔍 detectLanguageSync: calling detectLanguage API");
            log.info("[GCP Translation API] 🔍 Project: {}, Location: {}", properties.getLocation() != null ? properties.getLocation() : "global", properties.getProjectId());
            log.debug("[GCP Translation API] Text sample (first 100 chars): \"{}\"", textSample);

            DetectLanguageRequest request = DetectLanguageRequest.newBuilder()
                    .setParent(parent.toString())
                    .setContent(text)
                    .build();

            long startTime = System.currentTimeMillis();
            DetectLanguageResponse response = translationClient.detectLanguage(request);
            long duration = System.currentTimeMillis() - startTime;

            if (response.getLanguagesCount() > 0) {
                DetectedLanguage detectedLanguage = response.getLanguages(0);
                String languageCode = detectedLanguage.getLanguageCode();
                float confidence = detectedLanguage.getConfidence();

                log.info("[GCP Translation API] 🔍 PRIMARY language detected: {} (confidence: {}), duration={}ms",
                        languageCode, confidence, duration);

                // Log all detected languages if multiple (for mixed-language debugging)
                if (response.getLanguagesCount() > 1) {
                    log.info("[GCP Translation API] 🔍 Alternative languages detected:");
                    for (int i = 1; i < Math.min(response.getLanguagesCount(), 5); i++) {
                        DetectedLanguage alt = response.getLanguages(i);
                        log.info("[GCP Translation API]    - {} (confidence: {})",
                                 alt.getLanguageCode(), alt.getConfidence());
                    }
                }

                return languageCode;
            }

            String textPreview = text.substring(0, Math.min(50, text.length()));
            log.warn("[GCP Translation API] ⚠️ No language detected (empty response)");
            log.debug("[GCP Translation API] Text sample: \"{}...\"", textPreview);
            return "und"; // Undetermined
        } catch (Exception e) {
            String textPreview = text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
            log.error("[GCP Translation API] ❌ Language detection error: {}", e.getMessage());
            log.debug("[GCP Translation API] Text sample: \"{}\"", textPreview);
            log.error("[GCP Translation API] ❌ Exception details:", e);
            return "und"; // Undetermined
        }
    }
}
