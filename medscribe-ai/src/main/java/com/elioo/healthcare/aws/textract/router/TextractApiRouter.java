package com.elioo.healthcare.aws.textract.router;

import com.elioo.healthcare.aws.textract.handler.TextractApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for generic AWS Textract API endpoints.
 *
 * <p>Defines REST endpoints for direct access to AWS Textract services.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "aws.textract.enabled", havingValue = "true", matchIfMissing = true)
@Configuration
public class TextractApiRouter {

    @Bean
    public RouterFunction<ServerResponse> textractRoutes(TextractApiHandler handler) {
        return RouterFunctions.route()
                .path("/api/aws/textract", builder -> builder
                        .POST("/analyze-document", handler::analyzeDocument)
                        .POST("/detect-text", handler::detectText)
                        .POST("/validate-image", handler::validateImage)
                )
                .build();
    }
}
