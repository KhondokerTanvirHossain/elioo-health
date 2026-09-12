package com.elioo.baymax.adapter.in.router;

import com.elioo.baymax.adapter.in.handler.BaymaxHealthHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * All Baymax routes live under {@link #BASE_PATH}. Same WebFlux functional style as MedScribe;
 * literal paths must be registered before {@code {variable}} paths (first match wins).
 */
@Configuration
public class BaymaxHealthRouter {

    public static final String BASE_PATH = "/api/v1/baymax";

    @Bean
    public RouterFunction<ServerResponse> baymaxHealthRoutes(BaymaxHealthHandler handler) {
        return RouterFunctions.route()
                .GET(BASE_PATH + "/health", handler::health)
                .build();
    }
}
