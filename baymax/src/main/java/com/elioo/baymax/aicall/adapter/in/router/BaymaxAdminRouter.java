package com.elioo.baymax.aicall.adapter.in.router;

import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.aicall.adapter.in.handler.AdminMetricsHandler;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.storage.adapter.in.handler.StorageSelfTestHandler;
import com.elioo.baymax.extraction.adapter.in.handler.RecropHandler;
import com.elioo.baymax.outbound.adapter.in.handler.ReviewGateHandler;
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
    public RouterFunction<ServerResponse> baymaxAdminRoutes(AdminMetricsHandler metrics, StorageSelfTestHandler storage,
                                                             RecropHandler recrop, ReviewGateHandler review,
                                                            AdminAuthFilter auth, ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .GET(BASE_PATH + "/metrics/weekly", metrics::weekly)
                .GET(BASE_PATH + "/storage/selftest", storage::selfTest)
                // literal before {id}: "recrop" must not be read as a document id
                .POST(BASE_PATH + "/documents/recrop", recrop::all)
                .POST(BASE_PATH + "/documents/{id}/recrop", recrop::one)
                .GET(BASE_PATH + "/messages/pending", review::pending)
                .POST(BASE_PATH + "/messages/{id}/approve", review::approve)
                .POST(BASE_PATH + "/messages/{id}/reject", review::reject)
                .filter(errors)
                .filter(auth)
                .build();
    }
}
