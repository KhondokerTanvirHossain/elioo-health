package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.LoginCookie;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W3-api, S1: the no-JS sign-in fallback at {@code /app/basic}. It is {@link SignInPages}' own pages and handlers
 * served under a second base, so every form {@code action}, every redirect and every link (the brand mark and the
 * language switch, followed to where it lands, included) stay under
 * {@code /app/basic} while the cookies, the {@code next=} checks and the copy are the same code. Over the real web UI
 * router ({@link WebUiTestKit}) with a mocked {@link WebAuthUseCase}; every request carries only the cookies a browser
 * would have been given, copied by hand from the previous answer.
 *
 * <p>The chain starts from {@code medioo-web/fixtures/seams/noscript-href.json}, the {@code <noscript>} href Next
 * renders (W3 {@code paths.test.ts} holds {@code basicSignInPath(held)} equal to the same file), so the link a family
 * without JavaScript taps is the link this test signed in through.
 */
class BasicSignInPagesTest {

    private static final Path SEAM = Path.of("../medioo-web/fixtures/seams/noscript-href.json");
    private static final Path SEAM_WRITTEN = Path.of("build/web-contract/seams/noscript-href.json");
    private static final String HASH = "d4".repeat(32);
    private static final String NUMBER = "+8801700000171";
    private static final String TYPED = "01700-000171";
    private static final String CODE = "483920";

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final WebUiTestKit kit;
    private final WebTestClient client;
    private final ObjectMapper json = new ObjectMapper();

    BasicSignInPagesTest() {
        properties.getWa().setPublicNumber("8801700000999");
        kit = new WebUiTestKit(auth, properties, true);
        client = kit.client;
        when(auth.requestCode(NUMBER)).thenReturn(Mono.just(new CodeRequest(HASH, CodeIssue.SENT)));
        Instant now = Instant.now();
        when(auth.verify(anyString(), anyString())).thenAnswer(i -> HASH.equals(i.getArgument(0)) && CODE.equals(i.getArgument(1))
                ? Mono.just(new IssuedSession("tok-basic", new WebSession(UUID.randomUUID(), PaperFixtures.FAMILY, now, now, now.plusSeconds(600))))
                : Mono.error(new com.elioo.baymax.web.application.port.in.CodeRejectedException(com.elioo.baymax.web.domain.VerifyFailure.WRONG)));
    }

    // ---- the seam --------------------------------------------------------------------------------------------

    /** {@code encodeURIComponent}, exactly: what Next's {@code basicSignInPath} applies to the held place. */
    static String encodeURIComponent(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }

    /**
     * The seam file is generated here and byte-compared, so it is a generated fixture like every other file under
     * {@code medioo-web/fixtures} ({@code fixtures-meta.test.ts}), and no hand edit can make the two sides disagree.
     */
    @Test
    void theNoScriptSeamIsTheCommittedFile() {
        String held = "/app/documents/" + PaperFixtures.DOC;
        Map<String, Object> seam = new LinkedHashMap<>();
        seam.put("_meta", Snapshot.meta(BasicSignInPagesTest.class,
                "the held place a signed-out paper link keeps, and the <noscript> href to the Java fallback that holds it"));
        seam.put("held", held);
        seam.put("href", "/app/basic?next=" + encodeURIComponent(held));
        Snapshot.matches(SEAM, SEAM_WRITTEN, Snapshot.json(seam), "cp baymax/" + SEAM_WRITTEN + " medioo-web/fixtures/seams/noscript-href.json");
    }

    @Test
    void theEncodingIsEncodeURIComponents() {
        // the characters encodeURIComponent leaves alone and the ones it escapes, so the helper above cannot drift
        assertThat(encodeURIComponent("/app/x y?a=1&b=!'()*~-._")).isEqualTo("%2Fapp%2Fx%20y%3Fa%3D1%26b%3D!'()*~-._");
    }

    private JsonNode seam() throws IOException {
        JsonNode seam = json.readTree(Files.readString(SEAM, StandardCharsets.UTF_8));
        assertThat(seam.path("href").asText()).as("the seam's href").startsWith("/app/basic?next=%2F");
        assertThat(seam.path("held").asText()).as("the seam's held place").startsWith("/app/documents/");
        return seam;
    }

    // ---- the pages -------------------------------------------------------------------------------------------

    private static MultiValueMap<String, String> form(String... kv) {
        MultiValueMap<String, String> m = new LinkedMultiValueMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.add(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String body(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    private static List<String> attrs(String html, String tag, String attr) {
        return Dom.parse(html).all.stream().filter(e -> e.tag().equals(tag)).map(e -> e.attrs().get(attr)).toList();
    }

    /** Every link's href on the page, the brand mark and the language toggle included. */
    private static List<String> links(String html) {
        return Dom.parse(html).all.stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")).toList();
    }

    private EntityExchangeResult<byte[]> post(String uri, MultiValueMap<String, String> form, Map<String, String> cookies) {
        WebTestClient.RequestBodySpec spec = client.post().uri(uri);
        cookies.forEach(spec::cookie);
        return spec.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form))
                .exchange().expectBody().returnResult();
    }

    private EntityExchangeResult<byte[]> get(String uri, Map<String, String> cookies) {
        // the bytes as given: a browser sends the href's %2F as it is, never encoded a second time
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(java.net.URI.create(uri));
        for (Map.Entry<String, String> c : cookies.entrySet()) {
            spec = spec.cookie(c.getKey(), c.getValue());
        }
        return spec.exchange().expectBody().returnResult();
    }

    /** The value a browser would keep for {@code name}, read from the raw {@code Set-Cookie} headers (both ways a page sets one). */
    private static String cookie(EntityExchangeResult<byte[]> r, String name) {
        ResponseCookie added = r.getResponseCookies().getFirst(name);
        if (added != null) {
            return added.getValue();
        }
        return r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .map(h -> java.net.HttpCookie.parse("set-cookie: " + h).get(0))
                .filter(c -> c.getName().equals(name)).map(java.net.HttpCookie::getValue).findFirst().orElse(null);
    }

    @Test
    void theBasicNumberPagePostsToBasic() {
        for (String uri : List.of("/app/basic", "/app/basic/")) {
            EntityExchangeResult<byte[]> r = get(uri, Map.of());
            assertThat(r.getStatus().value()).as(uri).isEqualTo(200);
            String html = body(r);
            assertThat(attrs(html, "form", "action")).as(uri + ": every form's action").containsExactly("/app/basic/login");
            assertThat(attrs(html, "input", "name")).as(uri + ": the number field").contains("whatsapp_number");
            assertThat(html).as(uri).doesNotContain("<script");
        }
    }

    @Test
    void aConfirmedNumberRedirectsToTheBasicCodePage() {
        EntityExchangeResult<byte[]> confirm = post("/app/basic/login", form("whatsapp_number", TYPED), Map.of());
        assertThat(confirm.getStatus().value()).isEqualTo(200);
        assertThat(attrs(body(confirm), "form", "action")).as("the yes posts to basic").containsExactly("/app/basic/login");
        assertThat(attrs(body(confirm), "a", "href")).as("change the number, under basic").contains("/app/basic/");

        EntityExchangeResult<byte[]> r = post("/app/basic/login", form("whatsapp_number", NUMBER, "confirmed", "1"), Map.of());
        assertThat(r.getStatus().value()).isEqualTo(303);
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/app/basic/verify");
        assertThat(cookie(r, LoginCookie.NAME)).as("the login cookie, as /app/login writes it").isEqualTo(LoginCookie.value(HASH, "171", true));
    }

    @Test
    void theBasicCodePagePostsToBasic() {
        EntityExchangeResult<byte[]> r = get("/app/basic/verify", Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "171", true)));
        assertThat(r.getStatus().value()).isEqualTo(200);
        String html = body(r);
        assertThat(attrs(html, "form", "action")).as("every form's action").containsExactly("/app/basic/verify");
        assertThat(links(html)).as("the other-number link").contains("/app/basic/");
        assertThat(html).doesNotContain("<script");

        EntityExchangeResult<byte[]> none = get("/app/basic/verify", Map.of());
        assertThat(none.getStatus().value()).as("no login cookie: back to the number").isEqualTo(303);
        assertThat(none.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/app/basic/");
    }

    // ---- every re-render and redirect stays under /app/basic -------------------------------------------------

    private static final Map<String, String> LOGIN = Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "171", true));

    @Test
    void anInvalidNumberReRendersUnderBasic() {
        EntityExchangeResult<byte[]> r = post("/app/basic/login", form("whatsapp_number", "12"), Map.of());
        assertThat(r.getStatus().value()).isEqualTo(200);
        String html = body(r);
        assertThat(html).as("the invalid-number copy").contains(kit.copy.t(Lang.BN, "login.number.invalid"));
        assertThat(attrs(html, "form", "action")).as("the number form, again under basic").containsExactly("/app/basic/login");
    }

    @Test
    void aWrongCodeReRendersUnderBasic() {
        EntityExchangeResult<byte[]> r = post("/app/basic/verify", form("code", "000000"), LOGIN);
        assertThat(r.getStatus().value()).isEqualTo(200);
        String html = body(r);
        assertThat(html).as("the wrong-code copy").contains(kit.copy.t(Lang.BN, "login.bad.wrong"));
        assertThat(attrs(html, "form", "action")).as("the code form, again under basic").containsExactly("/app/basic/verify");
        assertThat(links(html)).as("the other-number link, under basic").contains("/app/basic/").doesNotContain("/app/");
    }

    /** The {@code CodeRejectedException} branch for the refusals that are not WRONG. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.elioo.baymax.web.domain.VerifyFailure.class, names = {"EXPIRED", "BURNED"})
    void aRefusedCodeReRendersUnderBasic(com.elioo.baymax.web.domain.VerifyFailure failure) {
        when(auth.verify(HASH, "111111")).thenReturn(Mono.error(new com.elioo.baymax.web.application.port.in.CodeRejectedException(failure)));
        EntityExchangeResult<byte[]> r = post("/app/basic/verify", form("code", "111111"), LOGIN);
        assertThat(r.getStatus().value()).as(failure.name()).isEqualTo(200);
        String html = body(r);
        assertThat(html).as(failure + ": its own copy").contains(kit.copy.t(Lang.BN, SignInPages.refusalKey(failure),
                properties.getAuth().getOtpMaxAttempts()));
        assertThat(attrs(html, "form", "action")).as(failure + ": the code form, again under basic").containsExactly("/app/basic/verify");
        assertThat(links(html)).as(failure + ": the other-number link, under basic").contains("/app/basic/").doesNotContain("/app/");
    }

    /** The second refusal branch: a plain 401 {@code BaymaxException}. */
    @Test
    void anUnauthorizedCodeReRendersUnderBasic() {
        when(auth.verify(HASH, "222222")).thenReturn(Mono.error(com.elioo.baymax.common.error.BaymaxException.unauthorized("bad_code", "no")));
        EntityExchangeResult<byte[]> r = post("/app/basic/verify", form("code", "222222"), LOGIN);
        assertThat(r.getStatus().value()).isEqualTo(200);
        String html = body(r);
        assertThat(html).as("the wrong-code copy").contains(kit.copy.t(Lang.BN, "login.bad.wrong"));
        assertThat(attrs(html, "form", "action")).as("the code form, again under basic").containsExactly("/app/basic/verify");
        assertThat(links(html)).as("the other-number link, under basic").contains("/app/basic/").doesNotContain("/app/");
    }

    @Test
    void aCodeWithoutTheLoginCookieGoesBackToTheBasicNumber() {
        EntityExchangeResult<byte[]> r = post("/app/basic/verify", form("code", CODE), Map.of());
        assertThat(r.getStatus().value()).isEqualTo(303);
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/app/basic/");
    }

    /**
     * Every link on every {@code /app/basic} page, resolved to where a tap actually lands: a {@code /lang/*} link is
     * followed through the router to its 303, so the language toggle's {@code back=} is checked, not its {@code /lang}
     * prefix. Every local landing stays under {@code /app/basic}; the code page's toggle keeps the code step. Only
     * {@code https://wa.me} leaves, and it is not a page of ours.
     */
    @Test
    void everyLinkOnABasicPageLandsUnderBasic() {
        Map<String, EntityExchangeResult<byte[]>> pages = new LinkedHashMap<>();
        pages.put("number", get("/app/basic/", Map.of()));
        pages.put("number, no slash", get("/app/basic", Map.of()));
        pages.put("confirm", post("/app/basic/login", form("whatsapp_number", TYPED), Map.of()));
        pages.put("invalid number", post("/app/basic/login", form("whatsapp_number", "12"), Map.of()));
        pages.put("code, sent", get("/app/basic/verify", LOGIN));
        pages.put("code, not sent", get("/app/basic/verify", Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "171", false))));
        pages.put("wrong code", post("/app/basic/verify", form("code", "000000"), LOGIN));
        int toggles = 0;
        List<String> offBasic = new ArrayList<>();
        for (Map.Entry<String, EntityExchangeResult<byte[]>> page : pages.entrySet()) {
            assertThat(page.getValue().getStatus().value()).as(page.getKey()).isEqualTo(200);
            List<String> hrefs = links(body(page.getValue()));
            assertThat(hrefs).as(page.getKey() + ": links read").isNotEmpty();
            boolean codeStep = page.getKey().startsWith("code") || page.getKey().equals("wrong code");
            for (String href : hrefs) {
                if (href.startsWith("https://wa.me/")) {
                    continue;
                }
                String lands = href;
                if (href.startsWith("/lang/")) {
                    toggles++;
                    lands = get(href, Map.of()).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
                    if (codeStep && !"/app/basic/verify".equals(lands)) {
                        offBasic.add(page.getKey() + ": " + href + " -> " + lands + " (the language switch must keep the code step)");
                        continue;
                    }
                }
                if (lands == null || !lands.startsWith("/app/basic")) {
                    offBasic.add(page.getKey() + ": " + href + " -> " + lands);
                }
            }
        }
        assertThat(offBasic).as("links on /app/basic pages that land outside /app/basic").isEmpty();
        assertThat(toggles).as("language links followed, so the sweep is not vacuous").isEqualTo(2 * pages.size());
    }

    /** DR-42, the Java half of the no-JS proof (S1): from the {@code <noscript>} href to the held page, with no script. */
    @Test
    void aNoJsSignInThroughBasicOpensTheHeldPage() throws IOException {
        JsonNode seam = seam();
        String held = seam.get("held").asText();
        List<EntityExchangeResult<byte[]>> chain = new ArrayList<>();

        EntityExchangeResult<byte[]> number = get(seam.get("href").asText(), Map.of());
        chain.add(number);
        assertThat(number.getStatus().value()).isEqualTo(200);
        String next = cookie(number, LoginCookie.NEXT_NAME);
        assertThat(next).as("the held place, kept for the code step").isEqualTo(held);

        EntityExchangeResult<byte[]> confirm = post(form0(number), form("whatsapp_number", TYPED), Map.of(LoginCookie.NEXT_NAME, next));
        chain.add(confirm);
        assertThat(confirm.getStatus().value()).isEqualTo(200);

        EntityExchangeResult<byte[]> requested = post(form0(confirm), form("whatsapp_number", NUMBER, "confirmed", "1"),
                Map.of(LoginCookie.NEXT_NAME, next));
        chain.add(requested);
        assertThat(requested.getStatus().value()).isEqualTo(303);
        String codePage = requested.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(codePage).isEqualTo("/app/basic/verify");
        String login = cookie(requested, LoginCookie.NAME);
        assertThat(login).isNotNull();

        EntityExchangeResult<byte[]> code = get(codePage, Map.of(LoginCookie.NAME, login, LoginCookie.NEXT_NAME, next));
        chain.add(code);
        assertThat(code.getStatus().value()).isEqualTo(200);

        EntityExchangeResult<byte[]> verified = post(form0(code), form("code", CODE), Map.of(LoginCookie.NAME, login, LoginCookie.NEXT_NAME, next));
        chain.add(verified);
        assertThat(verified.getStatus().value()).isEqualTo(303);
        assertThat(verified.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).as("the held page").isEqualTo(held);
        assertThat(cookie(verified, "baymax_session")).as("a session").isEqualTo("tok-basic");

        for (EntityExchangeResult<byte[]> r : chain) {
            assertThat(body(r)).as(r.getMethod() + " " + r.getUrl() + " carries no script").doesNotContain("<script");
        }
    }

    /** The one form on a page: where a browser without JavaScript posts it. */
    private static String form0(EntityExchangeResult<byte[]> page) {
        List<String> actions = attrs(body(page), "form", "action");
        assertThat(actions).as("one form on " + page.getUrl()).hasSize(1);
        return actions.get(0);
    }

    /** {@code SignInApiHandlerTest.aHostileHeldPlaceOpensHomeAndIsNeverHeld}'s own shapes, read from its annotation. */
    static Stream<String> hostile() throws NoSuchMethodException {
        // the class is package-private in the api package: read by name, its annotation is public
        String[] values;
        try {
            values = Class.forName("com.elioo.baymax.web.adapter.in.api.SignInApiHandlerTest")
                    .getDeclaredMethod("aHostileHeldPlaceOpensHomeAndIsNeverHeld", String.class).getAnnotation(ValueSource.class).strings();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("SignInApiHandlerTest moved: the hostile shapes are read from it", e);
        }
        assertThat(values).as("the hostile shapes read from SignInApiHandlerTest").isNotEmpty();
        return Stream.of(values);
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void aHostileNextThroughBasicOpensHome(String hostile) {
        EntityExchangeResult<byte[]> number = client.get().uri(b -> b.path("/app/basic").queryParam("next", "{n}").build(hostile))
                .exchange().expectBody().returnResult();
        assertThat(number.getStatus().value()).isEqualTo(200);
        assertThat(cookie(number, LoginCookie.NEXT_NAME)).as("never held: " + hostile).isNull();

        // the cookie is the client's to write: forged into it, the same value opens home (a backslash is not a cookie
        // character, so that shape is tried through the query only)
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put(LoginCookie.NAME, LoginCookie.value(HASH, "171", true));
        if (hostile.indexOf('\\') < 0) {
            cookies.put(LoginCookie.NEXT_NAME, hostile);
        }
        EntityExchangeResult<byte[]> verified = post("/app/basic/verify", form("code", CODE), cookies);
        assertThat(verified.getStatus().value()).isEqualTo(303);
        assertThat(verified.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).as(hostile).isEqualTo(LoginCookie.HOME);
    }

    @Test
    void theOldPathsAreUnchanged() {
        EntityExchangeResult<byte[]> r = post("/app/login", form("whatsapp_number", NUMBER, "confirmed", "1"), Map.of());
        assertThat(r.getStatus().value()).isEqualTo(303);
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/app/verify");
        assertThat(attrs(body(get("/app/", Map.of())), "form", "action")).containsExactly("/app/login");
        String code = body(get("/app/verify", Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "171", true))));
        assertThat(attrs(code, "form", "action")).containsExactly("/app/verify");
        assertThat(links(code)).contains("/app/").doesNotContain("/app/basic/");
    }
}
