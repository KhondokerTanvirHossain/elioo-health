package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BMX-6b: Baymax serves /, the app lives at /app, old /baymax URLs 301, the language is a cookie. */
class BaymaxWebUiRouterTest {

    /**
     * A Bangladeshi mobile number however it is written: +880 / 880 / 0, ASCII or Bangla digits, with or without a
     * space or hyphen between groups. Java's \\d does not match Bangla digits, and the page is in Bangla.
     */
    static final java.util.regex.Pattern BD_MOBILE = java.util.regex.Pattern.compile(
            "(?:\\+?[8৮][8৮][0০][\\s-]?|[0০])[1১][3-9৩-৯](?:[\\s-]?[0-9০-৯]){8}");

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final WebTestClient client;

    BaymaxWebUiRouterTest() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, new BaymaxProperties());
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        WebUiHandler ui = new WebUiHandler(auth, mock(TimelineUseCase.class), sessions, new UiCopy(), mock(com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase.class), mock(com.elioo.baymax.nudge.application.port.out.NudgeDataPort.class));
        client = WebTestClient.bindToRouterFunction(new BaymaxWebUiRouter().baymaxWebUiRoutes(ui, sessions)).build();
    }

    /** The page check is only as good as its pattern: every way the number is written, and not a date or a price. */
    @Test
    void thePhonePatternCatchesEveryWayANumberIsWrittenAndNothingElse() {
        for (String number : java.util.List.of("+8801700000000", "8801700000000", "01700000000", "০১৭০০০০০০০০",
                "+৮৮০১৭০০০০০০০০", "+880 1700-000000", "+880 1700 000 000", "০১৭০০-০০০০০০")) {
            assertThat(BD_MOBILE.matcher(number).find()).as(number).isTrue();
        }
        for (String other : java.util.List.of("২০২৬-০৯-২১", "2026-09-21", "১.১ → ১.৩ → ১.৫", "৳ ১,২০০", "HbA1c 8.2")) {
            assertThat(BD_MOBILE.matcher(other).find()).as(other).isFalse();
        }
    }

    @Test
    void theLandingPageIsBanglaByDefaultAndCarriesTheDisclaimerAndNoExternalRequests() {
        String html = client.get().uri("/").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html").expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).startsWith("<!doctype html><html lang=\"bn\">");
        assertThat(html).contains("চিকিৎসা পরামর্শ নয়").contains("Medioo").contains("Elioo Health");
        // every CTA is the app login until WhatsApp exists (BMX-10): no wa.me, no dead button, no placeholder address
        assertThat(html).contains("class=\"cta\" href=\"/app\"").doesNotContain("wa.me").doesNotContain("example.com").doesNotContain("XXXX");
        assertThat(html).contains("href=\"mailto:k.tanvir.hossain@gmail.com\"").doesNotContainPattern(BD_MOBILE);   // no phone number, in any form, is on the page
        // the trend renders once, inside its own span — not a span between every character
        assertThat(html).containsOnlyOnce("<span class=\"nw\">১.১ → ১.৩ → ১.৫</span>").doesNotContain("<span class=\"nw\"></span>");
        assertThat(html).doesNotContain("<script").doesNotContain("src=\"http").doesNotContain("href=\"http://");
        // the only off-site href is the company site in the footer (DR-15); every asset is same-origin
        assertThat(html.replace("href=\"https://www.eliooo.org/\"", "")).doesNotContain("https://");
        assertThat(html).containsPattern("<link rel=\"stylesheet\" href=\"/assets/baymax\\.css\\?v=[0-9a-f]+\">");
    }

    @Test
    void theLanguageCookieSwitchesEveryPageAndTheLoginPageToo() {
        String en = client.get().uri("/").cookie("baymax_lang", "en").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(en).startsWith("<!doctype html><html lang=\"en\">").contains("not medical advice").contains("Send your first photo");
        // the title is escaped exactly once: an apostrophe is &#39;, never &amp;#39;
        assertThat(en).contains("<title>Medioo — Your family&#39;s health companion</title>").doesNotContain("&amp;#39;");
        assertThat(en).doesNotContain("চিকিৎসা পরামর্শ নয়");

        String login = client.get().uri("/app/").cookie("baymax_lang", "en").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(login).contains("Your WhatsApp number").contains("not medical advice").doesNotContain("হোয়াটসঅ্যাপ");
    }

    @Test
    void switchingLanguageSetsAYearLongRootCookieAndGoesBackOnlyToALocalPath() {
        client.get().uri("/lang/en?back=/app/home").exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home")
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c).startsWith("baymax_lang=en").contains("Path=/").contains("Max-Age=31536000"));
        client.get().uri("/lang/en?back=https://evil.example/x").exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
        client.get().uri("/lang/en?back=//evil.example/x").exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
        client.get().uri("/lang/xx").exchange().expectStatus().isSeeOther()
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c).startsWith("baymax_lang=bn"));
    }

    /** Acceptance: every old /baymax/* URL 301s to its new location; a saved timeline link still opens. */
    @Test
    void oldBaymaxUrlsRedirectPermanentlyToApp() {
        UUID id = UUID.randomUUID();
        client.get().uri("/baymax/patients/" + id + "?cursor=abc").exchange().expectStatus().isEqualTo(301)
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/patients/" + id + "?cursor=abc");
        client.get().uri("/baymax/documents/" + id).exchange().expectStatus().isEqualTo(301)
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/documents/" + id);
        client.get().uri("/baymax/").exchange().expectStatus().isEqualTo(301).expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
        client.get().uri("/baymax").exchange().expectStatus().isEqualTo(301).expectHeader().valueEquals(HttpHeaders.LOCATION, "/app");
    }

    @Test
    void appPagesBehindASessionSendTheBrowserToTheLoginPage() {
        client.get().uri("/app/home").exchange().expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
    }

    @Test
    void assetsServeOnlyTheNamedFilesFromTheJar() {
        client.get().uri("/assets/baymax.css").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/css").expectHeader().cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePublic())
                .expectBody(String.class).value(css -> assertThat(css).contains(":root{"));
        client.get().uri("/assets/ui_bn.properties").exchange().expectStatus().isNotFound();
        client.get().uri("/assets/mio.webp").exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith("image/webp");
        client.get().uri("/assets/..%2Fmessages_bn.properties").exchange().expectStatus().isNotFound();
    }

    @Test
    void everyPageCarriesTheDisclaimerFooter() {
        java.time.Instant now = java.time.Instant.now();
        when(auth.authenticate("tok")).thenReturn(Mono.just(new com.elioo.baymax.web.domain.WebSession(
                UUID.randomUUID(), UUID.randomUUID(), now, now, now.plusSeconds(60))));
        // landing, the login page, and a rendered error page (a document id that is not a UUID → 404 page)
        for (String path : new String[]{"/", "/app/", "/app/documents/not-a-uuid"}) {
            WebTestClient.RequestHeadersSpec<?> req = client.get().uri(path);
            if (path.startsWith("/app/documents")) {
                req = req.cookie("baymax_session", "tok");   // the login page would redirect a signed-in visitor
            }
            String html = req.exchange().expectBody(String.class).returnResult().getResponseBody();
            assertThat(html).as(path).contains("<footer class=\"foot\">").contains("চিকিৎসা পরামর্শ নয়");
        }
    }

    /**
     * Regression guard: the code field's own {@code pattern} was {@code [0-9]{6}}, ASCII only, so the browser refused a
     * code typed on a Bangla keyboard before the server could fold it. The rule is read from the page served, and
     * applied the way a browser applies it (anchored, whole value), in both directions.
     */
    @Test
    void theCodeFieldLetsBanglaDigitsThroughAndStillRefusesWhatIsNotACode() {
        String html = client.get().uri("/app/verify").cookie(WebUiHandler.LOGIN_COOKIE, "a".repeat(64))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        java.util.regex.Matcher input = java.util.regex.Pattern.compile("<input[^>]*name=\"code\"[^>]*>").matcher(html);
        assertThat(input.find()).as("the verify page has a code field").isTrue();
        java.util.regex.Matcher attr = java.util.regex.Pattern.compile("pattern=\"([^\"]*)\"").matcher(input.group());
        assertThat(attr.find()).as("the code field carries a pattern: " + input.group()).isTrue();
        // Browsers compile pattern as a JS regex with the v flag; Java's engine is used here as a stand-in. For a
        // plain character class with ranges, as now, the two agree. A pattern using syntax where they differ (\p{..},
        // set operations, \d inside v-mode classes, nested classes) is not proven by this test: check it in a browser.
        java.util.regex.Pattern browser = java.util.regex.Pattern.compile("^(?:" + attr.group(1) + ")$");

        for (String code : java.util.List.of("123456", "০১২৩৪৫", "৬৭৮৯০৯")) {
            assertThat(browser.matcher(code).matches()).as("pattern \"%s\" must accept %s", attr.group(1), code).isTrue();
        }
        for (String notACode : java.util.List.of("12345", "১২৩৪৫", "1234567", "12345a", "abcdef")) {
            assertThat(browser.matcher(notACode).matches()).as("pattern \"%s\" must refuse %s", attr.group(1), notACode).isFalse();
        }
    }
}
