package com.elioo.baymax.web.adapter.in.api;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Every (method, pattern) a {@link RouterFunction} publishes, read with Spring's own visitor API — never a hand list. A
 * nested {@code path(...)} prefix is joined onto its routes; a route with no method predicate (the {@code /app/api/**}
 * catch-all) is method {@code *}. A route or predicate this walk cannot read fails the walk rather than being skipped.
 */
final class RouteWalk {

    record Route(String method, String pattern) implements Comparable<Route> {
        String line() {
            return method + " " + pattern;
        }

        @Override
        public int compareTo(Route o) {
            int byPattern = pattern.compareTo(o.pattern);
            return byPattern != 0 ? byPattern : method.compareTo(o.method);
        }
    }

    private RouteWalk() {
    }

    static List<Route> of(RouterFunction<?> router) {
        List<Route> routes = new ArrayList<>();
        Deque<String> prefixes = new ArrayDeque<>();
        router.accept(new RouterFunctions.Visitor() {
            @Override public void startNested(RequestPredicate predicate) {
                Collector c = Collector.of(predicate);
                prefixes.push(prefix() + (c.path == null ? "" : c.path));
            }
            @Override public void endNested(RequestPredicate predicate) {
                prefixes.pop();
            }
            @Override public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
                Collector c = Collector.of(predicate);
                if (c.path == null) {
                    throw new IllegalStateException("a route without a path: " + predicate);
                }
                String pattern = prefix() + c.path;
                if (c.methods.isEmpty()) {
                    routes.add(new Route("*", pattern));
                } else {
                    c.methods.forEach(m -> routes.add(new Route(m.name(), pattern)));
                }
            }
            @Override public void resources(Function<ServerRequest, Mono<Resource>> lookup) {
                throw new IllegalStateException("a resource router the walk cannot read: " + lookup);
            }
            @Override public void attributes(Map<String, Object> attributes) { }
            @Override public void unknown(RouterFunction<?> routerFunction) {
                throw new IllegalStateException("a router the walk cannot read: " + routerFunction);
            }

            private String prefix() {
                return prefixes.isEmpty() ? "" : prefixes.peek();
            }
        });
        return List.copyOf(routes);
    }

    private static final class Collector implements RequestPredicates.Visitor {
        final Set<HttpMethod> methods = new LinkedHashSet<>();
        String path;

        static Collector of(RequestPredicate predicate) {
            Collector c = new Collector();
            predicate.accept(c);
            return c;
        }

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
            throw new IllegalStateException("a predicate the walk cannot read: " + predicate);
        }
    }
}
