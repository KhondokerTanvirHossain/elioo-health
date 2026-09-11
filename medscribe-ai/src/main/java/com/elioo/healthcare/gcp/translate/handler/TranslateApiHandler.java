package com.elioo.healthcare.gcp.translate.handler;

import com.elioo.healthcare.gcp.translate.api.TranslationService;
import com.elioo.healthcare.gcp.translate.dto.*;
import com.elioo.healthcare.gcp.translate.model.TranslationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Handler for GCP Translation API web endpoints.
 *
 * <p>Provides reactive HTTP request handlers for Google Cloud Translation operations:
 * <ul>
 *   <li>Single text translation</li>
 *   <li>Batch translation</li>
 *   <li>Language detection</li>
 * </ul>
 *
 * <p><b>Architecture:</b> This handler acts as the web layer adapter for the
 * TranslationService, transforming HTTP requests into service calls and mapping
 * service responses to HTTP responses.</p>
 *
 * @since 1.0.0
 * @see TranslationService
 * @see TranslateApiRouter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TranslateApiHandler {

    private final TranslationService translationService;

    /**
     * Handle single text translation requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/translate/translate}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "text": "রক্তচাপ",
     *   "sourceLanguage": "bn",
     *   "targetLanguage": "en"
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Translated text with detected language and confidence.</p>
     *
     * @param request ServerRequest containing TranslateTextRequest
     * @return Mono&lt;ServerResponse&gt; with TranslateTextResponse or error
     */
    public Mono<ServerResponse> translateText(ServerRequest request) {
        return request.bodyToMono(TranslateTextRequest.class)
                .doOnNext(req -> log.info("GCP Translate: Translating text from {} to {}",
                        req.sourceLanguage() != null ? req.sourceLanguage() : "auto-detect",
                        req.targetLanguage()))
                .flatMap(req -> {
                    TranslationRequest translationRequest = TranslationRequest.builder()
                            .text(req.text())
                            .sourceLanguage(req.sourceLanguage())
                            .targetLanguage(req.targetLanguage())
                            .build();
                    return translationService.translate(translationRequest);
                })
                .map(response -> new TranslateTextResponse(
                        response.getTranslatedText(),
                        response.getDetectedSourceLanguage(),
                        response.getTargetLanguage(),
                        1.0  // GCP doesn't provide confidence score
                ))
                .flatMap(response -> {
                    log.info("GCP Translate: Translation complete. Detected language: {}",
                            response.detectedSourceLanguage());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle batch translation requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/translate/batch}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "texts": ["রক্তচাপ", "হিমোগ্লোবিন", "গ্লুকোজ"],
     *   "sourceLanguage": "bn",
     *   "targetLanguage": "en"
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Batch translation results with success/failure status for each text.</p>
     *
     * @param request ServerRequest containing BatchTranslateRequest
     * @return Mono&lt;ServerResponse&gt; with BatchTranslateResponse or error
     */
    public Mono<ServerResponse> batchTranslate(ServerRequest request) {
        return request.bodyToMono(BatchTranslateRequest.class)
                .doOnNext(req -> log.info("GCP Translate: Batch translating {} texts from {} to {}",
                        req.texts().size(),
                        req.sourceLanguage() != null ? req.sourceLanguage() : "auto-detect",
                        req.targetLanguage()))
                .flatMapMany(req -> Flux.fromIterable(req.texts())
                        .flatMap(text -> {
                            TranslationRequest translationRequest = TranslationRequest.builder()
                                    .text(text)
                                    .sourceLanguage(req.sourceLanguage())
                                    .targetLanguage(req.targetLanguage())
                                    .build();
                            return translationService.translate(translationRequest)
                                    .map(response -> new BatchTranslateResponse.TranslationResult(
                                            text,
                                            response.getTranslatedText(),
                                            true,
                                            null
                                    ))
                                    .onErrorResume(error -> {
                                        log.warn("GCP Translate: Failed to translate '{}': {}",
                                                text, error.getMessage());
                                        return Mono.just(new BatchTranslateResponse.TranslationResult(
                                                text,
                                                text,  // Return original on failure
                                                false,
                                                error.getMessage()
                                        ));
                                    });
                        }))
                .collectList()
                .map(results -> {
                    int successCount = (int) results.stream()
                            .filter(BatchTranslateResponse.TranslationResult::success)
                            .count();
                    int failureCount = results.size() - successCount;

                    log.info("GCP Translate: Batch translation complete. Success: {}, Failure: {}",
                            successCount, failureCount);

                    return new BatchTranslateResponse(
                            results,
                            results.size(),
                            successCount,
                            failureCount
                    );
                })
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response))
                .onErrorResume(this::handleError);
    }

    /**
     * Handle language detection requests.
     *
     * <p><b>Endpoint:</b> {@code POST /api/gcp/translate/detect-language}</p>
     *
     * <p><b>Request Body:</b></p>
     * <pre>{@code
     * {
     *   "text": "রক্তচাপ: 120/80 mmHg"
     * }
     * }</pre>
     *
     * <p><b>Response:</b> Language detection result indicating if text is English.</p>
     *
     * @param request ServerRequest containing DetectLanguageRequest
     * @return Mono&lt;ServerResponse&gt; with DetectLanguageResponse or error
     */
    public Mono<ServerResponse> detectLanguage(ServerRequest request) {
        return request.bodyToMono(DetectLanguageRequest.class)
                .doOnNext(req -> log.info("GCP Translate: Detecting language"))
                .flatMap(req -> translationService.containsNonEnglish(req.text()))
                .map(isNonEnglish -> {
                    DetectLanguageResponse response = new DetectLanguageResponse(
                            isNonEnglish ? "non-en" : "en",
                            isNonEnglish ? "Non-English" : "English",
                            1.0,
                            !isNonEnglish
                    );
                    log.info("GCP Translate: Language detected: {}", response.languageName());
                    return response;
                })
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response))
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate HTTP error response.
     *
     * <p>Logs the error and returns a 500 Internal Server Error response with
     * error details in JSON format.</p>
     *
     * @param error The error that occurred
     * @return Mono&lt;ServerResponse&gt; with error details
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("GCP Translate API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "gcp-translate"
                ));
    }
}
