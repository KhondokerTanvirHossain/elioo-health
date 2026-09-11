package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.in.ContentTranslationUseCase;
import com.elioo.healthcare.medicalreport.application.port.in.ContentTranslationUseCase.TranslatedContent;
import com.elioo.healthcare.medicalreport.domain.Language;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Handler for content translation API endpoints.
 *
 * <p>Architecture: Inbound Adapter (Web Handler) in Hexagonal Architecture</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/v1/medical-report/{reportId}/translate/{resultType}?lang=bn</li>
 *   <li>POST /api/v1/medical-report/{reportId}/translate-batch</li>
 * </ul>
 *
 * @see ContentTranslationUseCase
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentTranslationHandler {

    private final ContentTranslationUseCase translationUseCase;

    /**
     * GET /api/v1/medical-report/{reportId}/translate/{resultType}?lang=bn
     *
     * Translate a specific result type to the requested language.
     *
     * @param request Server request with reportId and resultType path variables
     * @return Translated content or error response
     */
    public Mono<ServerResponse> translateContent(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        String resultType = request.pathVariable("resultType").toUpperCase();
        String langCode = request.queryParam("lang").orElse("en");
        Language language = Language.fromCode(langCode);

        log.info("GET /translate/{} for report {} to {}",
                resultType, reportId, language.getCode());

        return translationUseCase.getTranslatedContent(reportId, resultType, language)
            .flatMap(content -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toResponse(content)))
            .switchIfEmpty(ServerResponse.notFound().build())
            .onErrorResume(this::handleError);
    }

    /**
     * POST /api/v1/medical-report/{reportId}/translate-batch
     *
     * Translate multiple result types at once.
     *
     * Request body: { "resultTypes": ["CLINICAL_INSIGHTS", "RISK_ASSESSMENT"], "lang": "bn" }
     *
     * @param request Server request with batch translation parameters
     * @return List of translated content or error response
     */
    public Mono<ServerResponse> translateBatch(ServerRequest request) {
        String reportId = request.pathVariable("reportId");

        log.info("POST /translate-batch for report {}", reportId);

        return request.bodyToMono(BatchTranslateRequest.class)
            .flatMap(req -> {
                Language language = Language.fromCode(req.lang());
                List<String> types = req.resultTypes().stream()
                    .map(String::toUpperCase)
                    .toList();

                log.info("Batch translating {} types to {} for report {}",
                        types.size(), language.getCode(), reportId);

                return translationUseCase.getTranslatedContentBatch(reportId, types, language)
                    .flatMap(results -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new BatchTranslateResponse(
                            reportId,
                            language.getCode(),
                            results.stream().map(this::toResponse).toList()
                        )));
            })
            .onErrorResume(this::handleError);
    }

    /**
     * GET /api/v1/medical-report/{reportId}/translate/check?resultType=CLINICAL_INSIGHTS&lang=bn
     *
     * Check if a translation is cached for the given parameters.
     */
    public Mono<ServerResponse> checkTranslationCache(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        String resultType = request.queryParam("resultType").orElse("");
        String langCode = request.queryParam("lang").orElse("bn");
        Language language = Language.fromCode(langCode);

        log.info("GET /translate/check for report {} type {} lang {}",
                reportId, resultType, language.getCode());

        return translationUseCase.isTranslationCached(reportId, resultType, language)
            .flatMap(isCached -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "reportId", reportId,
                    "resultType", resultType,
                    "language", language.getCode(),
                    "isCached", isCached
                )));
    }

    /**
     * GET /api/v1/medical-report/languages
     *
     * Get list of supported languages.
     */
    public Mono<ServerResponse> getSupportedLanguages(ServerRequest request) {
        log.info("GET /languages - Returning supported languages");

        List<Map<String, String>> languages = List.of(
            Map.of(
                "code", Language.EN.getCode(),
                "name", Language.EN.getEnglishName(),
                "nativeName", Language.EN.getNativeName()
            ),
            Map.of(
                "code", Language.BN.getCode(),
                "name", Language.BN.getEnglishName(),
                "nativeName", Language.BN.getNativeName()
            )
        );

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("languages", languages));
    }

    // ==================== Private Helper Methods ====================

    private TranslateResponse toResponse(TranslatedContent content) {
        return new TranslateResponse(
            content.reportId(),
            content.resultType(),
            content.contentJson(),
            content.language().getCode(),
            content.language().getNativeName(),
            content.fromCache(),
            content.translatedAt() != null ? content.translatedAt().toString() : null
        );
    }

    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Translation error: {}", error.getMessage(), error);

        if (error instanceof IllegalArgumentException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ErrorResponse("Invalid request", error.getMessage()));
        }

        return ServerResponse.status(500)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new ErrorResponse("Translation failed", error.getMessage()));
    }

    // ==================== Request/Response Records ====================

    public record BatchTranslateRequest(
        List<String> resultTypes,
        String lang
    ) {}

    public record BatchTranslateResponse(
        String reportId,
        String language,
        List<TranslateResponse> translations
    ) {}

    public record TranslateResponse(
        String reportId,
        String resultType,
        String contentJson,
        String languageCode,
        String languageName,
        boolean fromCache,
        String translatedAt
    ) {}

    public record ErrorResponse(
        String error,
        String message
    ) {}
}
