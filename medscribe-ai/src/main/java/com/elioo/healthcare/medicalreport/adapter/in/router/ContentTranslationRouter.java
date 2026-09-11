package com.elioo.healthcare.medicalreport.adapter.in.router;

import com.elioo.healthcare.medicalreport.adapter.in.handler.ContentTranslationHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * Router configuration for content translation APIs.
 *
 * <p>Architecture: Inbound Adapter (Web Router) in Hexagonal Architecture</p>
 *
 * <p>Base Path: /api/v1/medical-report</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /{reportId}/translate/{resultType}?lang=bn - Translate single result type</li>
 *   <li>POST /{reportId}/translate-batch - Translate multiple result types</li>
 *   <li>GET /{reportId}/translate/check?resultType=...&lang=bn - Check translation cache</li>
 *   <li>GET /languages - Get supported languages</li>
 * </ul>
 *
 * <p>Query Parameters:</p>
 * <ul>
 *   <li>lang: Target language code (default: "en"). Supported: "en", "bn"</li>
 *   <li>resultType: Result type for cache check</li>
 * </ul>
 *
 * <p>Example Requests:</p>
 * <pre>
 * GET /api/v1/medical-report/RPT-123/translate/CLINICAL_INSIGHTS?lang=bn
 * GET /api/v1/medical-report/RPT-123/translate/RISK_ASSESSMENT?lang=bn
 * POST /api/v1/medical-report/RPT-123/translate-batch
 *   { "resultTypes": ["CLINICAL_INSIGHTS", "RECOMMENDATIONS"], "lang": "bn" }
 * GET /api/v1/medical-report/languages
 * </pre>
 */
@Configuration
public class ContentTranslationRouter {

    private static final String BASE_PATH = "/api/v1/medical-report";

    @Bean
    public RouterFunction<ServerResponse> translationRoutes(ContentTranslationHandler handler) {
        return RouterFunctions.route()
            // Get supported languages
            .GET(BASE_PATH + "/languages", handler::getSupportedLanguages)

            // Single result type translation
            .GET(BASE_PATH + "/{reportId}/translate/{resultType}", handler::translateContent)

            // Batch translation (multiple result types)
            .POST(BASE_PATH + "/{reportId}/translate-batch", handler::translateBatch)

            // Check if translation is cached
            .GET(BASE_PATH + "/{reportId}/translate/check", handler::checkTranslationCache)

            .build();
    }
}
