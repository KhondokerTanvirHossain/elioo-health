package com.elioo.baymax.family.adapter.in.router;

import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.family.adapter.in.handler.FamilyHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Family onboarding and delete-on-request. Behind the admin token until OTP arrives (BMX-5); errors are
 * rendered by {@link ErrorResponseFilter} as {@code {status, reason, message}}.
 */
@Configuration
public class BaymaxFamilyRouter {

    public static final String BASE_PATH = BaymaxHealthRouter.BASE_PATH;

    @Bean
    public RouterFunction<ServerResponse> baymaxFamilyRoutes(FamilyHandler handler, AdminAuthFilter auth,
                                                             ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .POST(BASE_PATH + "/families", handler::createFamily)
                .POST(BASE_PATH + "/families/{id}/patients", handler::addPatient)
                .POST(BASE_PATH + "/patients/{id}/share", handler::addShareMember)
                .DELETE(BASE_PATH + "/patients/{id}", handler::deletePatient)
                .DELETE(BASE_PATH + "/families/{id}", handler::deleteFamily)
                .filter(errors)
                .filter(auth)
                .build();
    }
}
