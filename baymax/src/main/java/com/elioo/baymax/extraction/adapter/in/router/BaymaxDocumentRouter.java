package com.elioo.baymax.extraction.adapter.in.router;

import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.extraction.adapter.in.handler.DocumentHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Document intake and status. Behind the admin token until OTP arrives (BMX-5), and errors are rendered by
 * {@link ErrorResponseFilter}, so a free-tier refusal reaches the caller as 402 with a reason.
 */
@Configuration
public class BaymaxDocumentRouter {

    public static final String BASE_PATH = BaymaxHealthRouter.BASE_PATH + "/documents";

    @Bean
    public RouterFunction<ServerResponse> baymaxDocumentRoutes(DocumentHandler handler, AdminAuthFilter auth,
                                                               ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .POST(BASE_PATH, handler::upload)
                .GET(BASE_PATH + "/{id}", handler::status)
                .filter(errors)
                .filter(auth)
                .build();
    }
}
