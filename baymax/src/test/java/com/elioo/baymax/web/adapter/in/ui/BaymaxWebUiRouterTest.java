package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BMX-6b: Baymax serves /, the app lives at /app, old /baymax URLs 301, the language is a cookie. */
class BaymaxWebUiRouterTest {

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final WebTestClient client;

    BaymaxWebUiRouterTest() {
        // the public number IS configured, so "no wa.me on the landing page" is tested against a number that could leak
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setPublicNumber("8801700000000");
        client = new WebUiTestKit(auth, props, true).client;
    }

    /**
     * The page check is only as good as its pattern: every way the number is written, raw and URL-encoded as this app
     * writes URLs (the language toggle's {@code ?back=}, the NOW page's wa.me text), and not a date, a price or an id.
     */
    @Test
    void thePhonePatternCatchesEveryWayANumberIsWrittenAndNothingElse() {
        for (String number : java.util.List.of("+8801700000000", "8801700000000", "01700000000", "০১৭০০০০০০০০",
                "+৮৮০১৭০০০০০০০০", "+880 1700-000000", "+880 1700 000 000", "০১৭০০-০০০০০০")) {
            assertThat(PhoneGuard.find(number)).as(number).isPresent();
            String back = "/lang/en?back=" + java.net.URLEncoder.encode("/app/x?to=" + number, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(PhoneGuard.find(back)).as(back).isPresent();
            String share = "https://wa.me/?text=" + java.net.URLEncoder.encode("কল " + number, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            assertThat(PhoneGuard.find(share)).as(share).isPresent();
        }
        for (String other : java.util.List.of("২০২৬-০৯-২১", "2026-09-21", "১.১ → ১.৩ → ১.৫", "৳ ১,২০০", "HbA1c 8.2",
                "value=\"19492940-0135-4005-952b-d3d76ee5b1d2\"", "key=f%2Fp%2F19492940-0135-4005-952B-D3D76EE5B1D2%2Fpage-1.jpg",
                "width:100%;", "%E0%A6 broken escape %zz", "/assets/baymax.css?v=7f3a01c9")) {
            assertThat(PhoneGuard.find(other)).as(other).isEmpty();
        }
    }

    @Test
    void theLandingPageIsBanglaByDefaultAndCarriesTheDisclaimerAndNoExternalRequests() {
        String html = client.get().uri("/").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html").expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).startsWith("<!doctype html><html lang=\"bn\">");
        assertThat(html).contains("চিকিৎসা পরামর্শ নয়").contains("Medioo").contains("Elioo Health");
        // every CTA is the app login until WhatsApp exists (BMX-10): no wa.me, no dead button, no placeholder address
        assertThat(html).contains("class=\"cta\" href=\"/app\"").doesNotContain("wa.me").doesNotContain("8801700000000").doesNotContain("example.com").doesNotContain("XXXX");
        assertThat(html).contains("href=\"mailto:k.tanvir.hossain@gmail.com\"");
        assertThat(PhoneGuard.find(html)).as("a phone number, in any form, on the landing page").isEmpty();
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

    /**
     * A7 regression guard: a backslash after the first slash is a second slash to a browser, so "/\\evil.example" (sent
     * as /%5C) names another site. The old rule ("starts with / and not //") accepted it, and URI.create then threw on
     * the backslash: the language switch answered 500. Now anything but a local page goes to the landing.
     */
    @Test
    void switchingLanguageNeverFollowsABackslashOffTheSite() {
        client.get().uri(java.net.URI.create("/lang/en?back=/%5Cevil.example")).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
        client.get().uri(java.net.URI.create("/lang/en?back=/%5C%5Cevil.example/x")).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
    }

    /**
     * The decoded half of the same rule, through the real switch: a malformed escape ("%ZZ", which decoding throws on)
     * made the switch answer 500, and a "%2e%2e" segment is "..", which the browser resolves to a page outside /app.
     * Both go to the landing; a well-formed local page is still followed.
     */
    @Test
    void switchingLanguageTurnsAMalformedEscapeOrADotSegmentIntoTheLandingNotA500() {
        client.get().uri(java.net.URI.create("/lang/en?back=/app/x%25ZZ")).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
        client.get().uri(java.net.URI.create("/lang/en?back=/app/%252e%252e/api/v1/baymax/me")).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/");
        client.get().uri(java.net.URI.create("/lang/en?back=/app/home")).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home");
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

    /** D4: the add sheet's script leaves the jar as JavaScript, so the browser runs it under script-src 'self'. */
    @Test
    void theUploadScriptIsServedAsJavascript() {
        client.get().uri("/assets/upload.js").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/javascript");
    }

    /** D1: the two self-hosted fonts leave the jar as woff2; their licence stays in it. The content check is FontCoverageTest. */
    @Test
    void theFontsAreServedAsWoff2FromTheJar() {
        for (String f : new String[]{"noto-sans-bengali.woff2", "noto-sans-latin.woff2"}) {
            client.get().uri("/assets/" + f).exchange().expectStatus().isOk()
                    .expectHeader().contentType("font/woff2")
                    .expectBody(byte[].class).value(b -> assertThat(new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("wOF2"));
        }
        client.get().uri("/assets/OFL-Noto.txt").exchange().expectStatus().isNotFound();
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
        String html = client.get().uri("/app/verify").cookie(SignInPages.LOGIN_COOKIE, "a".repeat(64))
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

    /** Spec §10: never maximum-scale or user-scalable=no (WCAG 1.4.4); colour-scheme and theme-color follow the tokens. */
    @Test
    void everyPageCanBeZoomedAndFollowsTheSystemTheme() {
        Map<String, String> pages = RenderedPages.all();
        assertThat(pages).hasSizeGreaterThan(10);
        pages.forEach((where, html) -> assertThat(html).as(where)
                .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
                .doesNotContain("maximum-scale").doesNotContain("user-scalable")
                .contains("<meta name=\"color-scheme\" content=\"light dark\">")
                .contains("<meta name=\"theme-color\" media=\"(prefers-color-scheme: light)\" content=\"" + Tokens.LIGHT_BG + "\">")
                .contains("<meta name=\"theme-color\" media=\"(prefers-color-scheme: dark)\" content=\"" + Tokens.DARK_BG + "\">"));
        assertThat(Tokens.LIGHT_BG).startsWith("#").isNotEqualTo(Tokens.DARK_BG);
    }

    /**
     * viewport-fit=cover lets a page draw under a notch, so every box that pads a page's content from the screen edge
     * keeps the safe-area insets in that padding: {@code main} on every rendered page and each landing {@code .wrap},
     * read from the cascade on the element itself. A box whose padding is 0 pads nothing and is not asked.
     */
    @Test
    void everyPageKeepsItsContentOutOfTheNotch() {
        java.util.List<Css.Rule> rules = Css.rules(new WebUiTestKit().servedText("/assets/baymax.css"));
        java.util.Set<String> failures = new java.util.TreeSet<>();
        int padded = 0;
        for (Map.Entry<String, String> page : RenderedPages.all().entrySet()) {
            for (Dom.Element e : Dom.parse(page.getValue()).all) {
                if (!e.tag().equals("main") && !e.classes().contains("wrap")) {
                    continue;
                }
                java.util.Optional<Css.Rule> w = Css.winner(rules, e, "padding", "padding-left", "padding-right");
                String value = w.map(r -> String.join(" ", r.decls().getOrDefault("padding", ""), r.decls().getOrDefault("padding-left", ""),
                        r.decls().getOrDefault("padding-right", ""))).orElse("").trim();
                if (value.isEmpty() || value.equals("0")) {
                    continue;
                }
                padded++;
                if (!value.contains("env(safe-area-inset-left)") || !value.contains("env(safe-area-inset-right)")) {
                    failures.add(page.getKey() + ": " + e.describe() + " pads with " + value + " (" + w.get().selector() + ")");
                }
            }
        }
        assertThat(padded).as("content boxes with side padding on the rendered pages").isGreaterThan(20);
        assertThat(failures).isEmpty();
    }
}
