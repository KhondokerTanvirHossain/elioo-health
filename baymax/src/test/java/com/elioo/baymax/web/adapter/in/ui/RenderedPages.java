package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every page the web UI renders, derived from the router: each GET route of {@link RouteTable} with each of its
 * {@link PageFixtures} fixtures (main and variants), in Bangla (no cookie, what a first visit gets) and in English, on
 * a fresh kit. Only the text/html answers are kept. A page added later is checked by every test that reads this
 * (TouchTargetTest, the head test, and from A4 on NoInlineCodeTest, UploadEntryPointsTest, FirstLoadBudgetTest,
 * PaperIsNeverMediooTest). {@link #exchange} and {@link #render} came from WebPagesCharacterizationTest (deleted in A3).
 */
final class RenderedPages {

    /** bn is sent as no cookie at all: what a first visit gets. */
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
                    EntityExchangeResult<byte[]> r = exchange(kit, route, f, lang);
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

    /** One call of a route with a fixture, in a language. */
    static EntityExchangeResult<byte[]> exchange(WebUiTestKit kit, RouteTable.Route route, PageFixtures.Fixture f, String lang) {
        WebTestClient.RequestBodySpec spec = kit.client.method(route.method()).uri(f.uri());
        if (f.signedIn()) {
            spec = spec.cookie("baymax_session", "tok");
        }
        if (!"bn".equals(lang)) {
            spec = spec.cookie("baymax_lang", lang);
        }
        for (var c : f.cookies().entrySet()) {
            spec = spec.cookie(c.getKey(), c.getValue());
        }
        WebTestClient.RequestHeadersSpec<?> ready = switch (f.body()) {
            case NONE -> spec;
            case FORM -> spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(new LinkedMultiValueMap<>(f.form().entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey(), e -> List.of(e.getValue()))))));
            case MULTIPART -> {
                MultipartBodyBuilder body = new MultipartBodyBuilder();
                body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).filename("page-1.jpg");
                yield spec.body(BodyInserters.fromMultipartData(body.build()));
            }
        };
        return ready.exchange().expectBody(byte[].class).returnResult();
    }

    /** Status, every response header (sorted, the cookie clock normalised), a blank line, the body. */
    static String render(WebUiTestKit kit, RouteTable.Route route, PageFixtures.Fixture f, String lang) {
        EntityExchangeResult<byte[]> r = exchange(kit, route, f, lang);
        StringBuilder out = new StringBuilder().append(r.getStatus().value()).append('\n');
        new TreeMap<>(r.getResponseHeaders()).forEach((name, values) -> values.forEach(v -> out.append(name).append(": ")
                .append(name.equalsIgnoreCase(HttpHeaders.SET_COOKIE) ? v.replaceAll("Expires=[^;]*GMT", "Expires=X") : v)
                .append('\n')));
        return out.append('\n').append(r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8)).toString();
    }
}
