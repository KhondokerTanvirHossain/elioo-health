package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

/**
 * Spec §3 (A5): every signed-in page is in the app shell — one {@code header.appbar}, one {@code nav.tabs}, and in it
 * exactly one link marked {@code aria-current="page"}, the tab the page belongs to. Asserted once, over every route the
 * router publishes that is not open ({@link SignedInRoutesNeedASessionTest#OPEN}), with every fixture and both
 * languages, read as elements, never as substrings. Which tab a page belongs to is declared below; a signed-in page
 * whose route is not declared fails here, so a page added later cannot slip out of the shell unseen, and a declared
 * route that no longer answers a page fails too, so the list cannot rot.
 */
class EverySignedInPageIsInTheShellTest {

    /** Route → the tab it lives under. Only routes that answer a 200 page belong here. */
    static final Map<String, Tab> UNDER = Map.ofEntries(
            entry("GET /app/home", Tab.CHAT),
            entry("GET /app/timeline", Tab.PAPERS),
            entry("GET /app/family", Tab.FAMILY),
            // A7: the add-report sheet's no-JS URL, drawn over the tab its back link names (চ্যাট when none)
            entry("GET /app/add", Tab.CHAT),
            entry("GET /app/patients/{id}", Tab.PAPERS),
            entry("GET /app/documents/{id}", Tab.PAPERS),
            entry("GET /app/documents/{id}/delete", Tab.PAPERS),
            entry("POST /app/documents/{id}/delete", Tab.PAPERS));
    // A6: the profile routes answer a redirect to the পরিবার tab, no page of their own

    @Test
    void everySignedInPageHasTheHeaderAndTheTabBarWithItsOwnTabCurrent() {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router).stream()
                .filter(r -> !SignedInRoutesNeedASessionTest.OPEN.contains(r.key())).toList();
        assertThat(routes).as("signed-in routes read from the router").hasSizeGreaterThanOrEqualTo(10);
        List<String> failures = new ArrayList<>();
        Set<String> answeredAPage = new TreeSet<>();
        int pages = 0;
        for (RouteTable.Route route : routes) {
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                for (String lang : RenderedPages.LANGS) {
                    WebUiTestKit kit = new WebUiTestKit();
                    f.stubs().accept(kit);
                    EntityExchangeResult<byte[]> r = RenderedPages.exchange(kit, route, f, lang);
                    MediaType type = r.getResponseHeaders().getContentType();
                    if (r.getStatus().value() != 200 || type == null || !type.isCompatibleWith(MediaType.TEXT_HTML)
                            || r.getResponseBody() == null) {
                        continue;
                    }
                    pages++;
                    answeredAPage.add(route.key());
                    String where = route.key() + (f.name().isEmpty() ? "" : " #" + f.name()) + " " + lang;
                    Tab under = UNDER.get(route.key());
                    if (under == null) {
                        failures.add(where + ": a signed-in page whose tab is not declared in UNDER");
                        continue;
                    }
                    check(Dom.parse(new String(r.getResponseBody(), StandardCharsets.UTF_8)), under, where, failures);
                }
            }
        }
        assertThat(pages).as("signed-in pages checked; none would make this vacuous").isGreaterThanOrEqualTo(16);
        assertThat(failures).isEmpty();
        assertThat(answeredAPage).as("every route declared in UNDER still answers a signed-in page").containsAll(UNDER.keySet());
    }

    private static void check(Dom dom, Tab under, String where, List<String> failures) {
        if (!dom.unclosed.isEmpty()) {
            failures.add(where + ": broken markup " + dom.unclosed);
            return;
        }
        List<Dom.Element> headers = dom.all.stream().filter(e -> e.tag().equals("header") && e.classes().contains("appbar")).toList();
        if (headers.size() != 1) {
            failures.add(where + ": " + headers.size() + " header.appbar");
        }
        List<Dom.Element> navs = dom.all.stream().filter(e -> e.tag().equals("nav") && e.classes().contains("tabs")).toList();
        if (navs.size() != 1) {
            failures.add(where + ": " + navs.size() + " nav.tabs");
            return;
        }
        List<Dom.Element> current = AppShellRoutesTest.subtree(navs.get(0)).stream()
                .filter(e -> "page".equals(e.attrs().get("aria-current"))).toList();
        if (current.size() != 1) {
            failures.add(where + ": " + current.size() + " tabs marked current");
        } else if (!current.get(0).tag().equals("a") || !under.path.equals(current.get(0).attrs().get("href"))) {
            failures.add(where + ": current tab " + current.get(0).describe() + ", expected " + under.path);
        }
        if (dom.all.stream().anyMatch(e -> e.classes().contains("top"))) {
            failures.add(where + ": the old top bar inside the shell");
        }
    }
}
