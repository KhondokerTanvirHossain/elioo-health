package com.elioo.healthcare.gcp.vision.router;

import com.elioo.healthcare.core.MedScribePaths;
import com.elioo.healthcare.gcp.vision.handler.VisionApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for GCP Vision API web endpoints.
 *
 * <p>Defines functional reactive routes for Google Cloud Vision OCR operations.</p>
 *
 * <p><b>Available Endpoints:</b></p>
 * <ul>
 *   <li>{@code POST /api/gcp/vision/detect-text} - Simple text extraction</li>
 *   <li>{@code POST /api/gcp/vision/analyze-document} - Full document OCR with structure</li>
 *   <li>{@code POST /api/gcp/vision/validate-image} - Pre-flight image quality validation</li>
 * </ul>
 *
 * <p><b>Architecture Pattern:</b> This router follows the Spring WebFlux functional
 * routing pattern, consistent with the AWS API routers (Textract, Comprehend Medical, Bedrock).</p>
 *
 * <p><b>Example cURL Requests:</b></p>
 * <pre>{@code
 * # Simple text detection
 * curl -X POST http://localhost:8080/api/gcp/vision/detect-text \
 *   -H "Content-Type: application/json" \
 *   -d '{"imageBase64":"iVBORw0KGgo..."}'
 *
 * # Full document analysis
 * curl -X POST http://localhost:8080/api/gcp/vision/analyze-document \
 *   -H "Content-Type: application/json" \
 *   -d '{"imageBase64":"iVBORw0KGgo...","languageHints":["bn","en"]}'
 *
 * # Image validation
 * curl -X POST http://localhost:8080/api/gcp/vision/validate-image \
 *   -H "Content-Type: application/json" \
 *   -d '{"imageBase64":"iVBORw0KGgo..."}'
 * }</pre>
 *
 * @since 1.0.0
 * @see VisionApiHandler
 */
@Configuration
public class VisionApiRouter {

    /**
     * Define routes for GCP Vision API endpoints.
     *
     * <p>All routes are prefixed with {@code /api/gcp/vision} and accept POST requests.</p>
     *
     * @param handler VisionApiHandler containing request processing logic
     * @return RouterFunction with configured routes
     */
    @Bean
    public RouterFunction<ServerResponse> visionRoutes(VisionApiHandler handler) {
        return RouterFunctions.route()
                .path(MedScribePaths.PREFIX + "/api/gcp/vision", builder -> builder
                        .POST("/detect-text", handler::detectText)
                        .POST("/analyze-document", handler::analyzeDocument)
                        .POST("/validate-image", handler::validateImage)
                )
                .build();
    }
}
