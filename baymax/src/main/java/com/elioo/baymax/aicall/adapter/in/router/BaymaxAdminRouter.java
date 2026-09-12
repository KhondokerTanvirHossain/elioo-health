package com.elioo.baymax.aicall.adapter.in.router;

import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.aicall.adapter.in.handler.AdminMetricsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Admin routes under {@code /api/v1/baymax/admin/**}; every one of them passes {@link AdminAuthFilter}. */
@Configuration
public class BaymaxAdminRouter {

    public static final String BASE_PATH = BaymaxHealthRouter.BASE_PATH + "/admin";

    @Bean
    public RouterFunction<ServerResponse> baymaxAdminRoutes(AdminMetricsHandler metrics, AdminAuthFilter auth) {
        return RouterFunctions.route()
                .GET(BASE_PATH + "/metrics/weekly", metrics::weekly)
                .filter(auth)
                .build();
    }
}
