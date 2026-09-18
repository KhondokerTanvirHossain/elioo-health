package com.elioo.healthcare.llm.router;

import com.elioo.healthcare.core.MedScribePaths;
import com.elioo.healthcare.llm.handler.LlmApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class LlmApiRouter {

    @Bean
    public RouterFunction<ServerResponse> llmRoutes(LlmApiHandler handler) {
        return RouterFunctions.route()
                .path(MedScribePaths.PREFIX + "/api/llm", b -> b
                        .POST("/invoke", handler::invoke)
                        .POST("/health/clinical-insights", handler::generateClinicalInsights)
                        .POST("/health/summary", handler::generateSummary)
                        .POST("/health/risk-assessment", handler::assessRisk)
                        .POST("/health/recommendations", handler::generateRecommendations)
                        .POST("/health/trend-analysis", handler::analyzeTrends)
                        .POST("/health/educational-content", handler::generateEducationalContent))
                .build();
    }
}
