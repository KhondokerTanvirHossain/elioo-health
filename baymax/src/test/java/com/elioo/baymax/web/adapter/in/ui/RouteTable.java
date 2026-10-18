package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Every (method, pattern) the web UI router publishes, read from the router with its own visitor API. */
final class RouteTable {

    record Route(HttpMethod method, String pattern) {
        String key() {
            return method.name() + " " + pattern;
        }
    }

    private RouteTable() {
    }

    static List<Route> of(RouterFunction<?> router) {
        List<Route> routes = new ArrayList<>();
        router.accept(new RouterFunctions.Visitor() {
            @Override public void startNested(RequestPredicate predicate) { }
            @Override public void endNested(RequestPredicate predicate) { }
            @Override public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
                Collector c = new Collector();
                predicate.accept(c);
                if (c.path == null || c.methods.isEmpty()) {
                    throw new IllegalStateException("a route without a method and a path: " + predicate);
                }
                c.methods.forEach(m -> routes.add(new Route(m, c.path)));
            }
            @Override public void resources(Function<ServerRequest, Mono<Resource>> lookup) {
                throw new IllegalStateException("resource routes are not used by the web UI");
            }
            @Override public void attributes(Map<String, Object> attributes) { }
            @Override public void unknown(RouterFunction<?> routerFunction) {
                throw new IllegalStateException("a router the table cannot read: " + routerFunction);
            }
        });
        return List.copyOf(routes);
    }

    private static final class Collector implements RequestPredicates.Visitor {
        final Set<HttpMethod> methods = new LinkedHashSet<>();
        String path;

        @Override public void method(Set<HttpMethod> m) { methods.addAll(m); }
        @Override public void path(String pattern) { path = pattern; }
        @Override public void pathExtension(String extension) { }
        @Override public void header(String name, String value) { }
        @Override public void queryParam(String name, String value) { }
        @Override public void startAnd() { }
        @Override public void and() { }
        @Override public void endAnd() { }
        @Override public void startOr() { }
        @Override public void or() { }
        @Override public void endOr() { }
        @Override public void startNegate() { }
        @Override public void endNegate() { }
        @Override public void unknown(RequestPredicate predicate) {
            throw new IllegalStateException("a predicate the table cannot read: " + predicate);
        }
    }
}
