package com.elioo.baymax.web.adapter.in.router;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.web.adapter.in.handler.AuthHandler;
import com.elioo.baymax.web.adapter.in.handler.TimelineApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * OTP login (no auth: it is the door) and the session-scoped API. The session filter is added last so it
 * runs outermost and a 401 is never rewritten by the error filter.
 */
@Configuration
public class BaymaxAuthRouter {

    public static final String AUTH_PATH = BaymaxHealthRouter.BASE_PATH + "/auth";

    @Bean
    public RouterFunction<ServerResponse> baymaxAuthRoutes(AuthHandler handler, ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .POST(AUTH_PATH + "/request", handler::request)
                .POST(AUTH_PATH + "/verify", handler::verify)
                .POST(AUTH_PATH + "/logout", handler::logout)
                .filter(errors)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> baymaxTimelineRoutes(TimelineApiHandler handler, SessionAuthFilter session,
                                                                ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .GET(BaymaxHealthRouter.BASE_PATH + "/me", handler::me)
                .GET(BaymaxHealthRouter.BASE_PATH + "/patients/{id}/timeline", handler::timeline)
                .filter(errors)
                .filter(session)
                .build();
    }
}
