package com.elioo.baymax.web.adapter.in.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RequestPredicates.queryParam;

/**
 * The walk reads what it can and refuses what it cannot, in both directions: plain, nested and method-less routes come
 * out as their own lines, and a predicate that would drop a route from the table, or collapse two into one, fails.
 */
class RouteWalkTest {

    private static final HandlerFunction<ServerResponse> OK = request -> ServerResponse.ok().build();

    @Test
    void plainNestedAndMethodlessRoutesAreRead() {
        var router = RouterFunctions.route()
                .GET("/a", OK)
                .nest(path("/n"), b -> b.POST("/b", OK))
                .route(path("/c/**"), OK)
                .build();
        assertThat(RouteWalk.of(router).stream().map(RouteWalk.Route::line))
                .containsExactlyInAnyOrder("GET /a", "POST /n/b", "* /c/**");
    }

    @Test
    void anOrPredicateFailsTheWalkInsteadOfLosingABranch() {
        var router = RouterFunctions.route(GET("/a").or(GET("/b")), OK);
        assertThatThrownBy(() -> RouteWalk.of(router)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read");
    }

    @Test
    void aNegatedPathFailsTheWalkInsteadOfBeingRecordedAsThePattern() {
        var router = RouterFunctions.route(GET("/a/**").and(path("/a/x").negate()), OK);
        assertThatThrownBy(() -> RouteWalk.of(router)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read");
    }

    @Test
    void headerAndQueryPredicatesFailTheWalkInsteadOfCollapsingTwoRoutes() {
        assertThatThrownBy(() -> RouteWalk.of(RouterFunctions.route(GET("/a").and(accept(MediaType.TEXT_HTML)), OK)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot read");
        assertThatThrownBy(() -> RouteWalk.of(RouterFunctions.route(GET("/a").and(queryParam("x", v -> true)), OK)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot read");
    }
}
