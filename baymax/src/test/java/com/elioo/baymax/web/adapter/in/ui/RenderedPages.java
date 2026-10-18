package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every page the web UI renders, derived from the router: each GET route of {@link RouteTable} with each of its
 * {@link PageFixtures} fixtures (main and variants), in Bangla and in English, on a fresh kit. Only the text/html
 * answers are kept. A page added later is checked by every test that reads this (TouchTargetTest, the head test, and
 * from A4 on NoInlineCodeTest, UploadEntryPointsTest, FirstLoadBudgetTest, PaperIsNeverMediooTest).
 */
final class RenderedPages {

    static final List<String> LANGS = List.of("bn", "en");

    private RenderedPages() {
    }

    /** {@code "GET <uri>[ #variant] <lang>"} → the HTML body. */
    static Map<String, String> all() {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        assertThat(routes).as("routes read from the router").hasSizeGreaterThanOrEqualTo(20);
        Map<String, String> pages = new LinkedHashMap<>();
        for (RouteTable.Route route : routes) {
            if (route.method() != HttpMethod.GET) {
                continue;
            }
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                for (String lang : LANGS) {
                    WebUiTestKit kit = new WebUiTestKit();
                    f.stubs().accept(kit);
                    WebTestClient.RequestHeadersSpec<?> spec = kit.client.get().uri(f.uri()).cookie("baymax_lang", lang);
                    if (f.signedIn()) {
                        spec = spec.cookie("baymax_session", "tok");
                    }
                    for (var c : f.cookies().entrySet()) {
                        spec = spec.cookie(c.getKey(), c.getValue());
                    }
                    EntityExchangeResult<byte[]> r = spec.exchange().expectBody(byte[].class).returnResult();
                    MediaType type = r.getResponseHeaders().getContentType();
                    if (type == null || !type.isCompatibleWith(MediaType.TEXT_HTML) || r.getResponseBody() == null) {
                        continue;
                    }
                    String key = "GET " + f.uri() + (f.name().isEmpty() ? "" : " #" + f.name()) + " " + lang;
                    pages.put(key, new String(r.getResponseBody(), StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(pages).as("HTML pages rendered from the router's GET routes").hasSizeGreaterThanOrEqualTo(10);
        return pages;
    }
}
