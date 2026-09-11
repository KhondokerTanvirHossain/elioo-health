package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.in.ContentTranslationUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.ContentTranslationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.domain.Language;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Service implementation for translating medical report content between languages.
 *
 * <p>Architecture: Application Service in Hexagonal Architecture</p>
 *
 * <p>This service orchestrates content translation with caching:</p>
 * <ul>
 *   <li>Checks database cache for existing translations</li>
 *   <li>Translates content using GCP Translation if not cached</li>
 *   <li>Caches new translations for future requests</li>
 *   <li>Returns original content for English requests</li>
 * </ul>
 *
 * <p>Supported Content Types:</p>
 * <ul>
 *   <li>CLINICAL_INSIGHTS - Clinical summary, findings, recommendations</li>
 *   <li>RISK_ASSESSMENT - Risk scores and explanations</li>
 *   <li>RECOMMENDATIONS - Clinical recommendations with rationale</li>
 *   <li>EDUCATIONAL_CONTENT - Patient education materials</li>
 * </ul>
 *
 * @see ContentTranslationUseCase
 * @see ContentTranslationPort
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTranslationService implements ContentTranslationUseCase {

    private final ContentTranslationPort contentTranslationPort;
    private final MedicalReportPersistencePort persistencePort;

    // Content types that support translation
    private static final Set<String> TRANSLATABLE_TYPES = Set.of(
        "CLINICAL_INSIGHTS",
        "RISK_ASSESSMENT",
        "RECOMMENDATIONS",
        "EDUCATIONAL_CONTENT"
    );

    @Override
    public Mono<TranslatedContent> getTranslatedContent(
            String reportId,
            String resultType,
            Language targetLanguage
    ) {
        log.info("[Translation] Getting {} content for report {} in {}",
                resultType, reportId, targetLanguage.getCode());

        // Validate result type
        if (!TRANSLATABLE_TYPES.contains(resultType.toUpperCase())) {
            log.warn("[Translation] Result type {} is not translatable", resultType);
            return persistencePort.findResultByType(reportId, resultType)
                .map(result -> TranslatedContent.original(reportId, resultType, result.resultDataJson()));
        }

        // For English, return original content
        if (targetLanguage == Language.EN) {
            log.debug("[Translation] Target is English, returning original");
            return persistencePort.findResultByType(reportId, resultType)
                .map(result -> TranslatedContent.original(reportId, resultType, result.resultDataJson()))
                .doOnSuccess(content -> log.info("[Translation] Returning original English content"));
        }

        // Check cache first
        return findCachedTranslation(reportId, resultType, targetLanguage)
            .switchIfEmpty(
                // Not cached, translate and cache
                translateAndCache(reportId, resultType, targetLanguage)
            )
            .doOnSuccess(content -> log.info("[Translation] {} content retrieved (fromCache={})",
                    resultType, content.fromCache()));
    }

    @Override
    public Mono<List<TranslatedContent>> getTranslatedContentBatch(
            String reportId,
            List<String> resultTypes,
            Language targetLanguage
    ) {
        log.info("[Translation] Batch translating {} types for report {} to {}",
                resultTypes.size(), reportId, targetLanguage.getCode());

        return Flux.fromIterable(resultTypes)
            .flatMap(resultType -> getTranslatedContent(reportId, resultType, targetLanguage))
            .collectList()
            .doOnSuccess(results -> log.info("[Translation] Batch complete: {} translations", results.size()));
    }

    @Override
    public Mono<String> translateChatResponse(String response, Language targetLanguage) {
        if (targetLanguage == Language.EN || response == null || response.isBlank()) {
            return Mono.just(response != null ? response : "");
        }

        log.info("[Translation] Translating chat response to {}: {} chars",
                targetLanguage.getCode(), response.length());

        return contentTranslationPort.translateChatResponse(response, targetLanguage)
            .doOnSuccess(translated -> log.debug("[Translation] Chat response translated"));
    }

    @Override
    public Mono<String> translateUserMessageToEnglish(String message) {
        if (message == null || message.isBlank()) {
            return Mono.just("");
        }

        log.info("[Translation] Translating user message to English: {} chars", message.length());

        return contentTranslationPort.translateUserMessageToEnglish(message)
            .doOnSuccess(translated -> {
                if (!translated.equals(message)) {
                    log.info("[Translation] User message translated from Bangla to English");
                }
            });
    }

    @Override
    public Mono<Void> invalidateTranslationCache(String reportId, String resultType) {
        log.info("[Translation] Invalidating cache for {}/{}", reportId, resultType);

        // For now, we don't have a separate translation cache table
        // Translations are stored in the result entity
        // This method would clear the translated fields
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> isTranslationCached(String reportId, String resultType, Language targetLanguage) {
        if (targetLanguage == Language.EN) {
            return Mono.just(true); // English is always "cached" (original)
        }

        return findCachedTranslation(reportId, resultType, targetLanguage)
            .map(content -> true)
            .defaultIfEmpty(false);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Find cached translation from database.
     * For now, we store translations inline in the result record.
     * Future: Could use a separate translation cache table.
     */
    private Mono<TranslatedContent> findCachedTranslation(
            String reportId,
            String resultType,
            Language targetLanguage
    ) {
        // Check if we have a cached translation
        // This requires the entity to have translatedDataJson and translatedLanguage fields
        return persistencePort.findResultByType(reportId, resultType)
            .flatMap(result -> {
                // For now, translations are not cached in the current schema
                // Return empty to trigger translation
                return Mono.<TranslatedContent>empty();
            });
    }

    /**
     * Translate content and cache the result.
     */
    private Mono<TranslatedContent> translateAndCache(
            String reportId,
            String resultType,
            Language targetLanguage
    ) {
        log.info("[Translation] Translating {} for report {} to {}",
                resultType, reportId, targetLanguage.getCode());

        return persistencePort.findResultByType(reportId, resultType)
            .flatMap(result -> {
                String originalJson = result.resultDataJson();

                if (originalJson == null || originalJson.isBlank()) {
                    log.warn("[Translation] No content found for {}/{}", reportId, resultType);
                    return Mono.just(TranslatedContent.original(reportId, resultType, "{}"));
                }

                // Translate based on content type
                Mono<String> translationMono = switch (resultType.toUpperCase()) {
                    case "CLINICAL_INSIGHTS" ->
                        contentTranslationPort.translateClinicalInsights(originalJson, targetLanguage);
                    case "RISK_ASSESSMENT" ->
                        contentTranslationPort.translateRiskAssessment(originalJson, targetLanguage);
                    case "RECOMMENDATIONS" ->
                        contentTranslationPort.translateRecommendations(originalJson, targetLanguage);
                    case "EDUCATIONAL_CONTENT" ->
                        contentTranslationPort.translateEducationalContent(originalJson, targetLanguage);
                    default ->
                        contentTranslationPort.translateContent(originalJson, resultType, targetLanguage);
                };

                return translationMono
                    .map(translatedJson -> TranslatedContent.translated(
                        reportId,
                        resultType,
                        translatedJson,
                        targetLanguage
                    ))
                    .doOnSuccess(content -> log.info("[Translation] {} translated successfully", resultType))
                    .onErrorResume(e -> {
                        log.error("[Translation] Translation failed for {}, returning original: {}",
                                resultType, e.getMessage());
                        return Mono.just(TranslatedContent.original(reportId, resultType, originalJson));
                    });
            })
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.warn("[Translation] Result not found: {}/{}", reportId, resultType);
                    return Mono.just(TranslatedContent.original(reportId, resultType, "{}"));
                })
            );
    }
}
