package com.elioo.healthcare.aws.comprehend.router;

import com.elioo.healthcare.aws.comprehend.handler.ComprehendMedicalApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for AWS Comprehend Medical API endpoints.
 *
 * <p>Defines REST endpoints for direct access to AWS Comprehend Medical NLP services.
 */
@Configuration
public class ComprehendMedicalApiRouter {

    @Bean
    public RouterFunction<ServerResponse> comprehendMedicalRoutes(ComprehendMedicalApiHandler handler) {
        return RouterFunctions.route()
                .path("/api/aws/comprehend-medical", builder -> builder
                        .POST("/detect-entities", handler::detectEntities)
                        .POST("/infer-icd10", handler::inferICD10Codes)
                        .POST("/infer-rxnorm", handler::inferRxNormCodes)
                )
                .build();
    }
}
