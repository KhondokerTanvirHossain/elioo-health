package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.web.adapter.in.LoginCookie;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W3-api: {@code GET /app/api/sign-in/state}, the code step read back from the {@code baymax_login} cookie Java
 * wrote, so the Next sign-in page can show the right step after the family comes back from WhatsApp without parsing a
 * cookie itself. Over the real {@link AppApiRouter} and {@link AppApiGuard}, a mocked {@link WebAuthUseCase}, and no
 * session anywhere: this is an open route.
 */
class SignInStateApiTest {

    private static final String HASH = "b2".repeat(32);
    private static final String NUMBER = "+8801700000123";
    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    SignInStateApiTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        properties.getWa().setPublicNumber("8801700000999");
    }

    private WebTestClient client() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        return WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, mock(AttentionUseCase.class), properties),
                new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(mock(UploadUseCase.class), properties, new UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                new HealthApiHandler(mock(HealthUseCase.class)),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                guard)).build();
    }

    private static EntityExchangeResult<byte[]> state(WebTestClient client, String loginCookie) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/app/api/sign-in/state");
        if (loginCookie != null) {
            spec = spec.cookie(LoginCookie.NAME, loginCookie);
        }
        return spec.exchange().expectBody().returnResult();
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    private JsonNode body(EntityExchangeResult<byte[]> r) throws Exception {
        return json.readTree(text(r));
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void withoutTheLoginCookieNothingIsPending() {
        EntityExchangeResult<byte[]> r = state(client(), null);
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("200 {\"pending\":false}");
    }

    @Test
    void aSentLoginIsPendingAndSent() throws Exception {
        EntityExchangeResult<byte[]> r = state(client(), LoginCookie.value(HASH, "123", true));
        assertThat(r.getStatus().value()).isEqualTo(200);
        JsonNode body = body(r);
        assertThat(body.get("pending").isBoolean()).isTrue();
        assertThat(body.get("pending").asBoolean()).isTrue();
        assertThat(body.get("sent").isBoolean()).isTrue();
        assertThat(body.get("sent").asBoolean()).isTrue();
        assertThat(body.get("number_tail").asText()).isEqualTo("123");
        assertThat(body.get("code_ttl_minutes").asLong()).isEqualTo(properties.getAuth().getOtpTtl().toMinutes());
        assertThat(body.get("max_attempts").asInt()).isEqualTo(properties.getAuth().getOtpMaxAttempts());
        assertThat(body.has("wa_link")).as("a public number is set").isTrue();
    }

    @Test
    void aNotSentLoginIsPendingAndNotSent() throws Exception {
        EntityExchangeResult<byte[]> r = state(client(), LoginCookie.value(HASH, "456", false));
        assertThat(r.getStatus().value()).isEqualTo(200);
        JsonNode body = body(r);
        assertThat(body.get("pending").asBoolean()).isTrue();
        assertThat(body.get("sent").isBoolean()).isTrue();
        assertThat(body.get("sent").asBoolean()).as("no .s mark: WhatsApp did not accept a code").isFalse();
        // a tail the request did not use here, so a constant tail fails
        assertThat(body.get("number_tail").asText()).isEqualTo("456");
    }

    /**
     * The pending body is the request's own answer plus {@code pending}, field for field, derived from what
     * {@code POST /app/api/sign-in/request} answers for the same outcome — not from a list written here.
     */
    @Test
    void theStateIsTheRequestsOwnFields() throws Exception {
        WebTestClient client = client();
        List<String> compared = new ArrayList<>();
        for (boolean sent : List.of(true, false)) {
            when(auth.requestCode(NUMBER)).thenReturn(Mono.just(new CodeRequest(HASH, sent ? CodeIssue.SENT : CodeIssue.OUTSIDE_WINDOW)));
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("whatsapp_number", NUMBER);
            ask.put("confirmed", true);
            EntityExchangeResult<String> requested = client.post().uri("/app/api/sign-in/request").header("X-Medioo", "1")
                    .header("Origin", AppApiGuardTest.ORIGIN).contentType(MediaType.APPLICATION_JSON).bodyValue(ask)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
            JsonNode asked = json.readTree(requested.getResponseBody());
            String cookie = requested.getResponseCookies().getFirst(LoginCookie.NAME).getValue();

            JsonNode stated = body(state(client, cookie));
            Set<String> expected = new TreeSet<>(keys(asked));
            expected.add("pending");
            assertThat(keys(stated)).as("sent=" + sent + ": the state's fields").isEqualTo(expected);
            asked.fieldNames().forEachRemaining(field -> {
                assertThat(stated.get(field)).as("sent=" + sent + " $." + field).isEqualTo(asked.get(field));
                compared.add(field);
            });
        }
        assertThat(compared).as("fields compared, so the comparison is not vacuous").contains("sent", "number_tail", "wa_link");
    }

    /** A cookie that is not of the shape Java writes: nothing is pending and no tail is echoed. */
    @Test
    void aMalformedCookieIsNotPending() {
        WebTestClient client = client();
        for (String malformed : List.of("not-a-hash", HASH.substring(2) + ".123", HASH + ".12", HASH + ".123.x", HASH.toUpperCase() + ".123")) {
            EntityExchangeResult<byte[]> r = state(client, malformed);
            assertThat(r.getStatus().value() + " " + text(r)).as(malformed).isEqualTo("200 {\"pending\":false}");
        }
    }

    /**
     * A deliberate split from verify (PR #136 departure 6): the pre-tail bare-hash form is a valid login cookie, and
     * {@code POST /app/api/sign-in/verify} still accepts a code for it, but the state reads it as not pending — it
     * carries no tail to show, and its 30 minutes ended long ago. A visitor holding one is asked for the number again
     * (which writes a fresh cookie); one who types a code anyway is still signed in. Both halves are asserted here, so
     * a change to either side is seen.
     */
    @Test
    void aPreTailCookieIsNotPendingThoughVerifyStillAcceptsIt() {
        WebTestClient client = client();
        EntityExchangeResult<byte[]> r = state(client, HASH);
        assertThat(r.getStatus().value() + " " + text(r)).as("the bare-hash form, read back").isEqualTo("200 {\"pending\":false}");

        java.time.Instant now = java.time.Instant.now();
        when(auth.verify(HASH, "123456")).thenReturn(Mono.just(new com.elioo.baymax.web.domain.IssuedSession("tok-pre-tail",
                new com.elioo.baymax.web.domain.WebSession(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), now, now, now.plusSeconds(600)))));
        EntityExchangeResult<byte[]> verified = client.post().uri("/app/api/sign-in/verify").header("X-Medioo", "1")
                .header("Origin", AppApiGuardTest.ORIGIN).cookie(LoginCookie.NAME, HASH)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", "123456"))
                .exchange().expectBody().returnResult();
        assertThat(verified.getStatus().value()).as("verify still accepts the bare-hash form").isEqualTo(200);
        org.mockito.Mockito.verify(auth).verify(HASH, "123456");
    }

    /**
     * The cookie has no integrity check of its own: any well-shaped {@code <64 hex>.<3 digits>[.s]} reads as pending.
     * That is safe because the state echoes only what the client already holds — its own tail and its own sent mark —
     * and looks nothing up: no code is requested and none is checked.
     */
    @Test
    void aClientWrittenWellShapedCookieIsEchoedAsItsOwn() throws Exception {
        String written = "ab".repeat(32) + ".987";
        JsonNode body = body(state(client(), written));
        assertThat(body.get("pending").asBoolean()).as("well shaped, so pending").isTrue();
        assertThat(body.get("number_tail").asText()).as("the client's own tail, echoed").isEqualTo("987");
        assertThat(body.get("sent").asBoolean()).as("no .s mark written").isFalse();
        org.mockito.Mockito.verify(auth, org.mockito.Mockito.never()).requestCode(anyString());
        org.mockito.Mockito.verify(auth, org.mockito.Mockito.never()).verify(anyString(), anyString());
    }

    @Test
    void theStateNeedsNoSessionButStaysUnderTheGuard() {
        Logger logger = (Logger) LoggerFactory.getLogger(AppApiGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        EntityExchangeResult<byte[]> r;
        try {
            r = state(client(), LoginCookie.value(HASH, "123", true));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(r.getStatus().value()).as("no session, still answered").isEqualTo(200);
        assertThat(r.getResponseHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage))
                .as("the guard's one access line, on the route's own pattern")
                .anyMatch(m -> m.startsWith("[baymax-api] GET /app/api/sign-in/state 200 "));
    }
}
