package com.elioo.healthcare.aws.bedrock.router;

import com.elioo.healthcare.aws.bedrock.handler.BedrockApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for generic AWS Bedrock API endpoints.
 *
 * <p>Defines REST endpoints for direct access to AWS Bedrock LLM services.
 */
@Configuration
public class BedrockApiRouter {

    @Bean
    public RouterFunction<ServerResponse> bedrockRoutes(BedrockApiHandler handler) {
        return RouterFunctions.route()
                .path("/api/aws/bedrock", builder -> builder
                        .POST("/invoke-model", handler::invokeModel)
                        .POST("/invoke-claude", handler::invokeClaude)
                )
                .build();
    }
}
