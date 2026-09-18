package com.elioo.healthcare.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * The PoC's single-file UI at {@code /medscribeai} and {@code /medscribeai/} (DR-11). Static assets are served
 * under the same prefix by {@code spring.webflux.static-path-pattern}; Boot's welcome page no longer sits on
 * {@code /}, which Baymax owns.
 */
@Configuration
public class MedScribeUiRouter {

    @Bean
    public RouterFunction<ServerResponse> medScribeUiRoutes() {
        ClassPathResource index = new ClassPathResource("static/index.html");
        return RouterFunctions.route()
                .GET(MedScribePaths.PREFIX, request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index))
                .GET(MedScribePaths.PREFIX + "/", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index))
                .build();
    }
}
