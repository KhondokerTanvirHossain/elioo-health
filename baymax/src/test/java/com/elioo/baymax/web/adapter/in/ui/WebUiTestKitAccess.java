package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * For tests outside this package (BaymaxCspRegisteredTest): the URI of every fixture of every route the web UI router
 * publishes, read from the router through {@link RouteTable} and {@link PageFixtures}. A route added later is in this
 * list the moment it is routed, and a route with no fixture fails here instead of being skipped.
 */
public final class WebUiTestKitAccess {

    private WebUiTestKitAccess() {
    }

    public static List<String> fixtureUris() {
        List<String> uris = new ArrayList<>();
        for (RouteTable.Route route : RouteTable.of(new WebUiTestKit().router)) {
            PageFixtures.allOf(route).forEach(f -> uris.add(f.uri()));
        }
        return List.copyOf(uris);
    }

    /**
     * Every route, with every fixture, called through the real router with {@code filter} in front of it: {@code
     * "<METHOD> <uri>[ #variant]"} → the value of {@code header} on the response the browser receives (null when
     * absent). Whatever the route answers (a page, a redirect, a 404, a stylesheet), it is in this map.
     */
    public static Map<String, String> headerOnEveryRoute(WebFilter filter, String header) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (RouteTable.Route route : RouteTable.of(new WebUiTestKit().router)) {
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                WebUiTestKit kit = new WebUiTestKit();
                f.stubs().accept(kit);
                WebTestClient client = WebTestClient.bindToRouterFunction(kit.router).webFilter(filter).build();
                EntityExchangeResult<byte[]> r = RenderedPages.exchange(client, route, f, "bn");
                String key = route.method().name() + " " + f.uri() + (f.name().isEmpty() ? "" : " #" + f.name());
                seen.put(key, r.getResponseHeaders().getFirst(header));
            }
        }
        return seen;
    }
}
