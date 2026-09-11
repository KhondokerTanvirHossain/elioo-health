package com.elioo.healthcare.llm.health.router;

import com.elioo.healthcare.llm.health.handler.BedrockHealthApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for AWS Bedrock Health API endpoints.
 *
 * <p>Defines REST endpoints for domain-specific health AI capabilities.
 */
@Configuration
public class BedrockHealthApiRouter {

    @Bean
    public RouterFunction<ServerResponse> bedrockHealthRoutes(BedrockHealthApiHandler handler) {
        return RouterFunctions.route()
                .path("/api/aws/bedrock/health", builder -> builder
                        .POST("/clinical-insights", handler::generateClinicalInsights)
                        .POST("/summary", handler::generateSummary)
                        .POST("/risk-assessment", handler::assessRisk)
                        .POST("/recommendations", handler::generateRecommendations)
                        .POST("/trend-analysis", handler::analyzeTrends)
                        .POST("/educational-content", handler::generateEducationalContent)
                )
                .build();
    }
}
