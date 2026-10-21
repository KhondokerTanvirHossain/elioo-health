package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Dom;
import com.elioo.baymax.web.adapter.in.ui.PageChrome;
import com.elioo.baymax.web.adapter.in.ui.SignInPages;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.VerifyFailure;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan Wave 2 B4a: sign-in as {@code /app/api} JSON, over the router with a mocked {@link WebAuthUseCase}, the real
 * {@link AppApiGuard} and the real {@link SessionAuthFilter}. Every POST sends {@code X-Medioo: 1}, the public
 * {@code Origin} and JSON, as the browser does; nothing here holds a session, because these are the two routes that
 * need none.
 */
class SignInApiHandlerTest {

    static final String ORIGIN = "https://medioo.eliooo.org";
    private static final String NUMBER = "+8801700000123";
    private static final String HASH = "a1".repeat(32);
    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4300001");
    private static final String HELD = "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4300003";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The JSON name each refusal must carry: the table in the Wave 2 plan, written down once, here. */
    private static final Map<VerifyFailure, String> NAMES = new EnumMap<>(Map.of(
            VerifyFailure.FORMAT, "CODE_FORMAT",
            VerifyFailure.WRONG, "CODE_WRONG",
            VerifyFailure.NO_CODE, "CODE_WRONG",
            VerifyFailure.EXPIRED, "CODE_EXPIRED",
            VerifyFailure.BURNED, "CODE_BURNED"));

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    SignInApiHandlerTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.requestCode(anyString())).thenReturn(Mono.just(new CodeRequest(HASH, CodeIssue.OUTSIDE_WINDOW)));
    }

    private WebTestClient client() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        properties.getWeb().setPublicOrigin(ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        MeApiHandler me = new MeApiHandler(timeline, mock(AttentionUseCase.class), properties);
        return WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(me,
                new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(mock(com.elioo.baymax.web.application.port.in.UploadUseCase.class), properties, new UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, java.time.Clock.systemUTC())),
                guard)).build();
    }

    /** The HTML code page, rendered by {@link SignInPages} over the same mocks and properties. */
    private WebTestClient pages() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        UiCopy copy = new UiCopy();
        return WebTestClient.bindToRouterFunction(
                new SignInPages(auth, sessions, copy, new PageChrome(copy, sessions), properties).routes(null)).build();
    }

    private static EntityExchangeResult<byte[]> post(WebTestClient client, String uri, Object body, Map<String, String> cookies) {
        WebTestClient.RequestBodySpec spec = client.post().uri(uri).header("X-Medioo", "1").header("Origin", ORIGIN);
        cookies.forEach(spec::cookie);
        return spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectBody().returnResult();
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    private static JsonNode json(EntityExchangeResult<byte[]> r) throws Exception {
        return JSON.readTree(text(r));
    }

    private static Map<String, Object> confirmed(String number) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("whatsapp_number", number);
        body.put("confirmed", true);
        return body;
    }

    /** The {@code Set-Cookie} values the response carries for one cookie name, raw. */
    private static List<String> setCookies(EntityExchangeResult<byte[]> r, String name) {
        return r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(name + "=")).toList();
    }

    private static String cookieValue(String setCookie) {
        return HttpCookie.parse("set-cookie: " + setCookie).get(0).getValue();
    }

    @Test
    void sentIsTrueOnlyWhenWhatsAppAcceptedTheCode() throws Exception {
        WebTestClient client = client();
        Set<String> notSentBodies = new HashSet<>();
        int notSent = 0;
        for (CodeIssue issue : CodeIssue.values()) {
            when(auth.requestCode(NUMBER)).thenReturn(Mono.just(new CodeRequest(HASH, issue)));
            EntityExchangeResult<byte[]> r = post(client, "/app/api/sign-in/request", confirmed("01700-000123"), Map.of());
            assertThat(r.getStatus().value()).as(issue + " status").isEqualTo(200);
            JsonNode body = json(r);
            List<String> login = setCookies(r, "baymax_login");
            assertThat(login).as(issue + " login cookie").hasSize(1);
            String value = cookieValue(login.get(0));
            boolean sent = issue == CodeIssue.SENT;
            assertThat(body.get("sent").asBoolean()).as(issue.name()).isEqualTo(sent);
            assertThat(value.endsWith(".s")).as(issue + " cookie " + value).isEqualTo(sent);
            assertThat(value).as(issue + " cookie holds the hash and the tail").startsWith(HASH + ".123");
            assertThat(body.get("number_tail").asText()).isEqualTo("123");
            assertThat(body.get("code_ttl_minutes").asInt()).isEqualTo(properties.getAuth().getOtpTtl().toMinutes());
            assertThat(body.get("max_attempts").asInt()).isEqualTo(properties.getAuth().getOtpMaxAttempts());
            if (!sent) {
                notSent++;
                notSentBodies.add(text(r));
            }
        }
        assertThat(notSent).as("the non-SENT issues compared, so the comparison is not vacuous").isGreaterThanOrEqualTo(5);
        assertThat(notSentBodies).as("every non-SENT answer is the same bytes: the JSON tells no more than SENT or not-SENT, as the HTML page does")
                .hasSize(1);
    }

    @Test
    void anUnconfirmedNumberIsShownBackAndNothingIsRequested() throws Exception {
        EntityExchangeResult<byte[]> r = post(client(), "/app/api/sign-in/request", Map.of("whatsapp_number", "01700-000123"), Map.of());
        assertThat(r.getStatus().value()).isEqualTo(200);
        assertThat(json(r).at("/confirm/number_display").asText()).isEqualTo(PhoneDisplay.full(NUMBER));
        assertThat(r.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).as("no cookie before the yes").isNull();
        verify(auth, never()).requestCode(any());
    }

    @Test
    void anInvalidNumberIsRefusedAndNothingIsRequested() {
        EntityExchangeResult<byte[]> r = post(client(), "/app/api/sign-in/request", confirmed("12"), Map.of());
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("400 {\"reason\":\"INVALID_NUMBER\"}");
        verify(auth, never()).requestCode(any());
    }

    /** Review Focus 5: the link is the code page's own, in the viewer's language; derived from the page, not written down. */
    @Test
    void theWaLinkIsTheCodePagesOwnLinkInTheViewersLanguage() throws Exception {
        properties.getWa().setPublicNumber("8801700000999");
        WebTestClient client = client();
        WebTestClient pages = pages();
        List<String> seen = new ArrayList<>();
        for (String lang : new String[]{null, "en"}) {
            Map<String, String> cookies = lang == null ? Map.of() : Map.of("baymax_lang", lang);
            String link = json(post(client, "/app/api/sign-in/request", confirmed(NUMBER), cookies)).get("wa_link").asText();
            WebTestClient.RequestHeadersSpec<?> page = pages.get().uri("/app/verify").cookie("baymax_login", HASH + ".123");
            if (lang != null) {
                page = page.cookie("baymax_lang", lang);
            }
            String html = page.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            List<String> hrefs = Dom.parse(html).all.stream()
                    .filter(e -> e.tag().equals("a") && e.classes().containsAll(Set.of("btn", "wa")))
                    .map(e -> e.attrs().get("href")).toList();
            assertThat(hrefs).as(lang + ": the code page's WhatsApp button").hasSize(1);
            assertThat(link).as(lang + ": the JSON link is the page's").isEqualTo(hrefs.get(0));
            seen.add(link);
        }
        assertThat(seen.get(1)).as("English asks for the English word").endsWith("?text=code");
        assertThat(seen.get(0)).as("Bangla is the default, and its word is not the English one").doesNotEndWith("?text=code");
    }

    @Test
    void noPublicNumberMeansNoWaLink() throws Exception {
        properties.getWa().setPublicNumber("");
        JsonNode body = json(post(client(), "/app/api/sign-in/request", confirmed(NUMBER), Map.of()));
        assertThat(body.has("sent")).as("the confirmed answer").isTrue();
        assertThat(body.has("wa_link")).isFalse();
    }

    @Test
    void verifyRefusalsAreTypedAndNever401() {
        assertThat(NAMES.keySet()).as("every failure has its name in the table").containsExactlyInAnyOrder(VerifyFailure.values());
        WebTestClient client = client();
        for (VerifyFailure failure : VerifyFailure.values()) {
            when(auth.verify(HASH, "123456")).thenReturn(Mono.error(new CodeRejectedException(failure)));
            EntityExchangeResult<byte[]> r = post(client, "/app/api/sign-in/verify", Map.of("code", "123456"),
                    Map.of("baymax_login", HASH + ".123"));
            assertThat(r.getStatus().value() + " " + text(r)).as(failure.name())
                    .isEqualTo("400 {\"reason\":\"" + NAMES.get(failure) + "\"}");
        }
    }

    /**
     * The HTML page's second rule ({@code SignInPages.verify}): any other 401 the use case raises on this path is the
     * wrong-code copy. In the JSON that is {@code CODE_WRONG}, never the guard's 401, which would send the client from
     * the sign-in page to the sign-in page.
     */
    @Test
    void anyOther401OnVerifyIsCodeWrongNever401() {
        when(auth.verify(HASH, "123456")).thenReturn(Mono.error(BaymaxException.unauthorized("x", "y")));
        EntityExchangeResult<byte[]> r = post(client(), "/app/api/sign-in/verify", Map.of("code", "123456"),
                Map.of("baymax_login", HASH + ".123"));
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("400 {\"reason\":\"CODE_WRONG\"}");
    }

    @Test
    void verifyWithoutTheLoginCookieAsksToStartAgain() {
        WebTestClient client = client();
        for (Map<String, String> cookies : List.of(Map.<String, String>of(), Map.of("baymax_login", "not-a-hash"))) {
            EntityExchangeResult<byte[]> r = post(client, "/app/api/sign-in/verify", Map.of("code", "123456"), cookies);
            assertThat(r.getStatus().value() + " " + text(r)).as(cookies.toString()).isEqualTo("400 {\"reason\":\"SIGN_IN_RESTART\"}");
        }
        verify(auth, never()).verify(anyString(), anyString());
    }

    @Test
    void aRightCodeOpensASessionAndReturnsTheHeldPlace() throws Exception {
        rightCode();
        EntityExchangeResult<byte[]> r = post(client(), "/app/api/sign-in/verify", Map.of("code", "123456"),
                Map.of("baymax_login", HASH + ".123", "baymax_next", HELD));
        assertThat(r.getStatus().value()).isEqualTo(200);
        assertThat(json(r).get("next").asText()).isEqualTo(HELD);
        assertThat(setCookies(r, "baymax_session")).singleElement().satisfies(c -> assertThat(cookieValue(c)).isEqualTo("tok-new"));
        for (String name : List.of("baymax_login", "baymax_next")) {
            assertThat(setCookies(r, name)).as(name + " cleared").singleElement().satisfies(c -> {
                HttpCookie cleared = HttpCookie.parse("set-cookie: " + c).get(0);
                assertThat(cleared.getMaxAge()).as(name + " Max-Age").isZero();
                assertThat(cleared.getValue()).as(name + " value").isEmpty();
            });
        }
    }

    /**
     * Request to verify with the bytes the request itself wrote: the held place survives the round trip as the
     * {@code baymax_next} the request set, and verify answers exactly it.
     */
    @Test
    void theHeldPlaceTheRequestWritesIsThePlaceVerifyReturns() throws Exception {
        rightCode();
        when(auth.requestCode(NUMBER)).thenReturn(Mono.just(new CodeRequest(HASH, CodeIssue.SENT)));
        WebTestClient client = client();
        Map<String, Object> body = confirmed(NUMBER);
        body.put("next", HELD);
        EntityExchangeResult<byte[]> requested = post(client, "/app/api/sign-in/request", body, Map.of());
        List<String> login = setCookies(requested, "baymax_login");
        List<String> next = setCookies(requested, "baymax_next");
        assertThat(login).as("login cookie set").hasSize(1);
        assertThat(next).as("next cookie set").hasSize(1);
        assertThat(cookieValue(next.get(0))).as("the held place, as written").isEqualTo(HELD);

        EntityExchangeResult<byte[]> verified = post(client, "/app/api/sign-in/verify", Map.of("code", "123456"),
                Map.of("baymax_login", cookieValue(login.get(0)), "baymax_next", cookieValue(next.get(0))));
        assertThat(verified.getStatus().value()).isEqualTo(200);
        assertThat(json(verified).get("next").asText()).isEqualTo(HELD);
        verify(auth).verify(HASH, "123456");
    }

    /** {@code $.next} feeds client-side navigation, so every hostile shape is tried on this path, held both ways. */
    @ParameterizedTest
    @ValueSource(strings = {"/app/api/me", "//evil.example", "/\\evil.example", "https://evil.example/app", "/app/%2e%2e/x"})
    void aHostileHeldPlaceOpensHomeAndIsNeverHeld(String hostile) throws Exception {
        rightCode();
        WebTestClient client = client();
        EntityExchangeResult<byte[]> verified = post(client, "/app/api/sign-in/verify", Map.of("code", "123456"),
                Map.of("baymax_login", HASH + ".123", "baymax_next", hostile));
        assertThat(json(verified).get("next").asText()).as("held in the cookie: " + hostile).isEqualTo("/app/home");

        Map<String, Object> body = confirmed(NUMBER);
        body.put("next", hostile);
        EntityExchangeResult<byte[]> requested = post(client, "/app/api/sign-in/request", body, Map.of());
        assertThat(requested.getStatus().value()).isEqualTo(200);
        assertThat(setCookies(requested, "baymax_next")).as("sent in the body: " + hostile).isEmpty();
        assertThat(setCookies(requested, "baymax_login")).as("the request itself went through").hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyCookieTheJsonSignInSetsIsScopedToApp(boolean secure) throws Exception {
        properties.getAuth().setCookieSecure(secure);
        rightCode();
        WebTestClient client = client();
        Map<String, Object> body = confirmed(NUMBER);
        body.put("next", HELD);
        List<String> headers = new ArrayList<>(post(client, "/app/api/sign-in/request", body, Map.of())
                .getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        headers.addAll(post(client, "/app/api/sign-in/verify", Map.of("code", "123456"),
                Map.of("baymax_login", HASH + ".123", "baymax_next", HELD)).getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));

        List<String> names = new ArrayList<>();
        for (String raw : headers) {
            HttpCookie c = HttpCookie.parse("set-cookie: " + raw).get(0);
            String sameSite = null;
            for (String attribute : raw.split(";")) {
                String a = attribute.strip();
                if (a.regionMatches(true, 0, "SameSite=", 0, 9)) {
                    sameSite = a.substring(9);
                }
            }
            String what = c.getName() + " Path=" + c.getPath();
            assertThat(c.getPath()).as(what).isEqualTo("/app");
            assertThat(c.isHttpOnly()).as(what + " HttpOnly").isTrue();
            assertThat(sameSite).as(what + " SameSite").isEqualTo("Lax");
            assertThat(c.getSecure()).as(what + " Secure").isEqualTo(secure);
            names.add(c.getName());
        }
        assertThat(names).as("every cookie the two calls set, set and cleared alike")
                .containsExactlyInAnyOrder("baymax_login", "baymax_next", "baymax_session", "baymax_login", "baymax_next");
    }

    /** The JSON tells no more than the HTML page already shows: two failures share a name exactly when they share copy. */
    @Test
    void everyJsonCodeRefusalIsTheHtmlPagesRefusal() throws Exception {
        WebTestClient client = client();
        Map<VerifyFailure, String> json = new EnumMap<>(VerifyFailure.class);
        Map<VerifyFailure, String> html = new HashMap<>();
        for (VerifyFailure failure : VerifyFailure.values()) {
            when(auth.verify(HASH, "123456")).thenReturn(Mono.error(new CodeRejectedException(failure)));
            json.put(failure, json(post(client, "/app/api/sign-in/verify", Map.of("code", "123456"),
                    Map.of("baymax_login", HASH + ".123"))).get("reason").asText());
            html.put(failure, SignInPages.refusalKey(failure));
        }
        assertThat(html.values()).as("the HTML keys").allSatisfy(k -> assertThat(k).startsWith("login.bad."));
        for (VerifyFailure a : VerifyFailure.values()) {
            for (VerifyFailure b : VerifyFailure.values()) {
                assertThat(json.get(a).equals(json.get(b))).as(a + " and " + b + " share a JSON name exactly when they share HTML copy")
                        .isEqualTo(html.get(a).equals(html.get(b)));
            }
        }
        assertThat(new HashSet<>(json.values())).as("distinct JSON names").hasSameSizeAs(new HashSet<>(html.values()));
    }

    private void rightCode() {
        when(auth.verify(HASH, "123456")).thenReturn(Mono.just(new IssuedSession("tok-new",
                new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600)))));
    }
}
