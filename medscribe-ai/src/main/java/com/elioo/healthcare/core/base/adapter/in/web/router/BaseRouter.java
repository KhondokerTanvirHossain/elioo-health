package com.elioo.healthcare.core.base.adapter.in.web.router;

import com.elioo.healthcare.core.base.adapter.in.web.handler.BaseHandler;
import com.elioo.healthcare.core.base.domain.BaseDomain;
import com.elioo.healthcare.core.routes.RouteNames;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Component
public class BaseRouter {

    public <T extends BaseDomain> RouterFunction<ServerResponse> baseRouterConfig(BaseHandler<T> handler, String baseUrl) {
        return RouterFunctions.route()
                .nest(RequestPredicates.accept(MediaType.APPLICATION_JSON), builder -> builder
                        .GET(baseUrl.concat(RouteNames.GET_BY_ID), handler::getById)
                        .GET(baseUrl.concat(RouteNames.LIST), handler::list)
                        .GET(baseUrl.concat(RouteNames.PAGE), handler::page)
                        .GET(baseUrl.concat(RouteNames.FILTER), handler::filter)
                        .GET(baseUrl.concat(RouteNames.LIST_FILTER), handler::listFilter)
                        .POST(baseUrl.concat(RouteNames.CREATE), handler::create)
                        .POST(baseUrl.concat(RouteNames.CREATE_LIST), handler::createList)
                        .PATCH(baseUrl.concat(RouteNames.UPDATE), handler::update)
                        .PATCH(baseUrl.concat(RouteNames.UPDATE_FILTER), handler::updateByFilter)
                )
                .build();
    }

    // Hook method for domain-specific routers to add custom routes
    public <T extends BaseDomain> RouterFunction<ServerResponse> customRoutes(BaseHandler<T> handler, String baseUrl) {
        return RouterFunctions.route().build(); // Empty by default, overridden by domain routers
    }

    // Combine base and custom routes
    public <T extends BaseDomain> RouterFunction<ServerResponse> routerConfig(BaseHandler<T> handler, String baseUrl) {
        return RouterFunctions.route()
                .add(baseRouterConfig(handler, baseUrl))
                .add(customRoutes(handler, baseUrl))
                .build();
    }
}