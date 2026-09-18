package com.elioo.healthcare.gcp.translate.router;

import com.elioo.healthcare.core.MedScribePaths;
import com.elioo.healthcare.gcp.translate.handler.TranslateApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for GCP Translation API web endpoints.
 *
 * <p>Defines functional reactive routes for Google Cloud Translation operations.</p>
 *
 * <p><b>Available Endpoints:</b></p>
 * <ul>
 *   <li>{@code POST /api/gcp/translate/translate} - Translate single text</li>
 *   <li>{@code POST /api/gcp/translate/batch} - Translate multiple texts</li>
 *   <li>{@code POST /api/gcp/translate/detect-language} - Detect if text is English</li>
 * </ul>
 *
 * <p><b>Architecture Pattern:</b> This router follows the Spring WebFlux functional
 * routing pattern, consistent with the AWS API routers and GCP Vision router.</p>
 *
 * <p><b>Example cURL Requests:</b></p>
 * <pre>{@code
 * # Single text translation
 * curl -X POST http://localhost:8080/api/gcp/translate/translate \
 *   -H "Content-Type: application/json" \
 *   -d '{"text":"রক্তচাপ","sourceLanguage":"bn","targetLanguage":"en"}'
 *
 * # Batch translation
 * curl -X POST http://localhost:8080/api/gcp/translate/batch \
 *   -H "Content-Type: application/json" \
 *   -d '{"texts":["রক্তচাপ","হিমোগ্লোবিন"],"sourceLanguage":"bn","targetLanguage":"en"}'
 *
 * # Language detection
 * curl -X POST http://localhost:8080/api/gcp/translate/detect-language \
 *   -H "Content-Type: application/json" \
 *   -d '{"text":"রক্তচাপ: 120/80 mmHg"}'
 * }</pre>
 *
 * @since 1.0.0
 * @see TranslateApiHandler
 */
@Configuration
public class TranslateApiRouter {

    /**
     * Define routes for GCP Translation API endpoints.
     *
     * <p>All routes are prefixed with {@code /api/gcp/translate} and accept POST requests.</p>
     *
     * @param handler TranslateApiHandler containing request processing logic
     * @return RouterFunction with configured routes
     */
    @Bean
    public RouterFunction<ServerResponse> translateRoutes(TranslateApiHandler handler) {
        return RouterFunctions.route()
                .path(MedScribePaths.PREFIX + "/api/gcp/translate", builder -> builder
                        .POST("/translate", handler::translateText)
                        .POST("/batch", handler::batchTranslate)
                        .POST("/detect-language", handler::detectLanguage)
                )
                .build();
    }
}
