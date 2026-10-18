package com.elioo.baymax.web.adapter.in.ui;

import java.util.ArrayList;
import java.util.List;

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
}
