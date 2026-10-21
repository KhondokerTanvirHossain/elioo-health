package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Each screen applies the router's {@code requireSession} to its own signed-in routes (R1), so the rule "no session,
 * no family data" is asserted once, here, over every route the router publishes — not per handler. A route is
 * signed-in unless it is declared open below: a route added later that forgets the filter fails here, and an open
 * route a family reaches from a message link without a session (the opt-out) fails if a filter is put on it.
 */
class SignedInRoutesNeedASessionTest {

    /** The routes a visitor without a session may reach. Everything else needs one. */
    static final Set<String> OPEN = Set.of(
            "GET /", "GET /lang/{code}", "GET /assets/{file}", "GET /baymax", "GET /baymax/{*rest}",
            "GET /app/", "GET /app", "POST /app/login", "GET /app/verify", "POST /app/verify", "POST /app/logout",
            "GET /app/nudges/opt-out", "POST /app/nudges/opt-out",
            // plan W3-api, S1: the no-JS sign-in fallback, the same pages under /app/basic
            "GET /app/basic/", "GET /app/basic", "POST /app/basic/login", "GET /app/basic/verify", "POST /app/basic/verify");

    /** What requireSession answers: 303 to the sign-in page, no cookie set, no family data read. */
    private static final String SESSION_REDIRECT = "303\nLocation: /app/\n\n";

    @Test
    void everyRouteNotDeclaredOpenSendsAVisitorWithoutASessionToSignIn() {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        List<RouteTable.Route> signedIn = routes.stream().filter(r -> !OPEN.contains(r.key())).toList();
        assertThat(signedIn).as("signed-in routes read from the router; none would make this check vacuous").hasSizeGreaterThanOrEqualTo(10);
        List<String> leaks = new ArrayList<>();
        for (RouteTable.Route route : signedIn) {
            WebUiTestKit kit = new WebUiTestKit();
            PageFixtures.Fixture f = PageFixtures.of(route);
            f.stubs().accept(kit);
            String answer = RenderedPages.render(kit, route, anonymous(f), "bn");
            if (!answer.equals(SESSION_REDIRECT)) {
                leaks.add(route.key() + " answered " + answer.substring(0, Math.min(answer.length(), 80)).replace("\n", "⏎"));
                continue;
            }
            verifyNoInteractions(kit.timeline, kit.optOut, kit.nudgeData);
        }
        assertThat(leaks).as("signed-in routes that answered a visitor without a session").isEmpty();
    }

    @Test
    void everyRouteDeclaredOpenAnswersAVisitorWithoutASession() {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        assertThat(routes.stream().map(RouteTable.Route::key).toList()).as("every route declared open is still a route").containsAll(OPEN);
        List<String> closed = new ArrayList<>();
        for (RouteTable.Route route : routes.stream().filter(r -> OPEN.contains(r.key())).toList()) {
            WebUiTestKit kit = new WebUiTestKit();
            PageFixtures.Fixture f = PageFixtures.of(route);
            f.stubs().accept(kit);
            if (RenderedPages.render(kit, route, anonymous(f), "bn").equals(SESSION_REDIRECT)) {
                closed.add(route.key());
            }
        }
        assertThat(closed).as("open routes that sent a visitor without a session to sign in").isEmpty();
    }

    private static PageFixtures.Fixture anonymous(PageFixtures.Fixture f) {
        return new PageFixtures.Fixture(f.uri(), false, f.body(), f.form(), f.cookies(), f.name(), f.stubs());
    }
}
