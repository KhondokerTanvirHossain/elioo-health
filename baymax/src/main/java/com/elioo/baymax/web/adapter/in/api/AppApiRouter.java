package com.elioo.baymax.web.adapter.in.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * The browser's JSON namespace for the Next.js frontend (rebuild plan A3, B1). It lives under {@code /app} so the
 * {@code Path=/app} session cookie travels with it and is never widened; Caddy sends {@code /app/api/**} to Java.
 *
 * <p>It is its own router with its own filter, {@link AppApiGuard}, and is ordered ahead of every other router bean, so
 * no request under {@code /app/api} ever reaches the HTML router's {@code requireSession} (a 303 to sign-in) or its HTML
 * error pages — today because no HTML route matches {@code /app/api/**}, and after any later HTML catch-all because of
 * this order ({@code AppApiRouteContractTest.anHtmlCatchAllUnderAppNeverCapturesAnApiRoute} registers one and fails if
 * the order goes). The last route catches any other {@code /app/api} path, so an unknown route answers
 * {@code {"reason":"NOT_FOUND"}} (after the session) instead of falling through to a page or Spring's error JSON:
 * a newer frontend on an older Java gets a reason it understands (A2: additive only).
 */
@Configuration
public class AppApiRouter {

    public static final String BASE = "/app/api";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> mediooAppApiRoutes(MeApiHandler me, AppApiGuard guard) {
        return RouterFunctions.route()
                .GET(BASE + "/me", me::me)
                .PUT(BASE + "/active-patient", me::setActivePatient)
                .route(RequestPredicates.path(BASE + "/**"), request -> ApiReason.NOT_FOUND.respond())
                .filter(guard)
                .build();
    }
}
