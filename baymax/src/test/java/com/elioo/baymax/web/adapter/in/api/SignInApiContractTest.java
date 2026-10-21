package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.LoginCookie;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W3-api: every answer the Next sign-in page reads — {@code POST /app/api/sign-in/request} and {@code verify},
 * {@code GET /app/api/sign-in/state} and {@code GET /app/api/sign-in/next} — one MSW fixture per case, in the
 * generated-file envelope, compared byte for byte with the committed {@code medioo-web/fixtures/api/sign-in/*.json}.
 * Over the real {@link AppApiRouter}, {@link AppApiGuard} and {@link SignInApiHandler}, with a mocked
 * {@link WebAuthUseCase}. Every POST is the browser's: {@code X-Medioo: 1}, the public {@code Origin}, JSON. Numbers are
 * fictional ({@code +8801700000xxx}); the WhatsApp link is to a fictional public number.
 */
class SignInApiContractTest {

    private static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/sign-in");
    private static final Path WRITTEN = Path.of("build/web-contract/sign-in");
    /** The visitor's number, E.164: the only number a sign-in fixture shows back ({@code confirm.number_display}). */
    static final String NUMBER = "+8801700000123";
    /**
     * Medioo's own public WhatsApp number (fictional here), the one {@code wa_link} opens: never a family's. Both are
     * exempted by value, and only by value, from {@code FamilyApiContractTest.theNumberAppearsOnlyAsTheAccountNumber}.
     */
    static final String PUBLIC_NUMBER = "8801700000999";
    private static final String HASH = "e5".repeat(32);
    private static final String CODE = "483920";
    private static final String HELD = "/app/documents/" + PaperFixtures.DOC;
    private static final Instant NOW = PaperFixtures.NOW;

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    SignInApiContractTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), PaperFixtures.FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        properties.getWa().setPublicNumber(PUBLIC_NUMBER);
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

    // ---- the cases ------------------------------------------------------------------------------------------

    /** One case: what the route says it is (for the fixture's source line), and how it is served and asked. */
    record Case(String route, Function<SignInApiContractTest, EntityExchangeResult<byte[]>> run) {
    }

    static Map<String, Case> cases() {
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("confirm", new Case("POST /app/api/sign-in/request", t -> t.request(Map.of("whatsapp_number", "01700-000123"))));
        cases.put("sent", new Case("POST /app/api/sign-in/request", t -> t.requested(CodeIssue.SENT)));
        cases.put("not-sent", new Case("POST /app/api/sign-in/request", t -> t.requested(CodeIssue.OUTSIDE_WINDOW)));
        cases.put("not-sent-no-link", new Case("POST /app/api/sign-in/request", t -> {
            t.properties.getWa().setPublicNumber("");
            return t.requested(CodeIssue.OUTSIDE_WINDOW);
        }));
        cases.put("invalid-number", new Case("POST /app/api/sign-in/request", t -> t.request(Map.of("whatsapp_number", "12", "confirmed", true))));
        cases.put("code-wrong", new Case("POST /app/api/sign-in/verify", t -> t.refused(VerifyFailure.WRONG)));
        cases.put("code-expired", new Case("POST /app/api/sign-in/verify", t -> t.refused(VerifyFailure.EXPIRED)));
        cases.put("code-burned", new Case("POST /app/api/sign-in/verify", t -> t.refused(VerifyFailure.BURNED)));
        cases.put("code-format", new Case("POST /app/api/sign-in/verify", t -> t.refused(VerifyFailure.FORMAT)));
        cases.put("restart", new Case("POST /app/api/sign-in/verify", t -> t.verify(Map.of())));
        cases.put("verified", new Case("POST /app/api/sign-in/verify", t -> {
            when(t.auth.verify(HASH, CODE)).thenReturn(Mono.just(new IssuedSession("tok-new",
                    new WebSession(UUID.randomUUID(), PaperFixtures.FAMILY, NOW, NOW, NOW.plusSeconds(600)))));
            return t.verify(Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "123", true), LoginCookie.NEXT_NAME, HELD));
        }));
        cases.put("state-pending-sent", new Case("GET /app/api/sign-in/state", t -> t.state(LoginCookie.value(HASH, "123", true))));
        cases.put("state-pending-not-sent", new Case("GET /app/api/sign-in/state", t -> t.state(LoginCookie.value(HASH, "123", false))));
        cases.put("state-none", new Case("GET /app/api/sign-in/state", t -> t.state(null)));
        cases.put("next-held", new Case("GET /app/api/sign-in/next", t -> t.next(HELD)));
        cases.put("next-hostile", new Case("GET /app/api/sign-in/next", t -> t.next("//evil.example/app")));
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    private EntityExchangeResult<byte[]> request(Map<String, Object> body) {
        return client().post().uri("/app/api/sign-in/request").header("X-Medioo", "1").header("Origin", AppApiGuardTest.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectBody().returnResult();
    }

    private EntityExchangeResult<byte[]> requested(CodeIssue issue) {
        when(auth.requestCode(NUMBER)).thenReturn(Mono.just(new CodeRequest(HASH, issue)));
        return request(Map.of("whatsapp_number", NUMBER, "confirmed", true));
    }

    private EntityExchangeResult<byte[]> verify(Map<String, String> cookies) {
        WebTestClient.RequestBodySpec spec = client().post().uri("/app/api/sign-in/verify").header("X-Medioo", "1")
                .header("Origin", AppApiGuardTest.ORIGIN);
        cookies.forEach(spec::cookie);
        return spec.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", CODE)).exchange().expectBody().returnResult();
    }

    private EntityExchangeResult<byte[]> refused(VerifyFailure failure) {
        when(auth.verify(HASH, CODE)).thenReturn(Mono.error(new CodeRejectedException(failure)));
        return verify(Map.of(LoginCookie.NAME, LoginCookie.value(HASH, "123", false)));
    }

    private EntityExchangeResult<byte[]> state(String login) {
        WebTestClient.RequestHeadersSpec<?> spec = client().get().uri("/app/api/sign-in/state");
        if (login != null) {
            spec = spec.cookie(LoginCookie.NAME, login);
        }
        return spec.exchange().expectBody().returnResult();
    }

    private EntityExchangeResult<byte[]> next(String raw) {
        return client().get().uri(b -> b.path("/app/api/sign-in/next").queryParam("next", "{n}").build(raw))
                .cookie("baymax_session", "tok").exchange().expectBody().returnResult();
    }

    private JsonNode body(String name) throws IOException {
        return json.readTree(cases().get(name).run().apply(this).getResponseBody());
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) throws IOException {
        Case c = cases().get(name);
        EntityExchangeResult<byte[]> r = c.run().apply(this);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(SignInApiContractTest.class, c.route() + " over the SignInApiContractTest case " + name));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/sign-in/" + file);
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).as("committed fixtures vs the cases").containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    /** Each case is what its name says, by field: a fixture named for one state cannot quietly hold another. */
    @Test
    void theCasesShowWhatTheirNamesSay() throws IOException {
        assertThat(body("confirm").at("/confirm/number_display").asText()).isNotBlank();
        assertThat(body("sent").get("sent").asBoolean()).isTrue();
        assertThat(body("sent").has("wa_link")).isTrue();
        assertThat(body("not-sent").get("sent").asBoolean()).isFalse();
        assertThat(body("not-sent").has("wa_link")).isTrue();
        assertThat(body("not-sent-no-link").get("sent").asBoolean()).isFalse();
        assertThat(body("not-sent-no-link").has("wa_link")).isFalse();
        properties.getWa().setPublicNumber(PUBLIC_NUMBER);
        assertThat(body("invalid-number").get("reason").asText()).isEqualTo("INVALID_NUMBER");
        assertThat(body("code-wrong").get("reason").asText()).isEqualTo("CODE_WRONG");
        assertThat(body("code-expired").get("reason").asText()).isEqualTo("CODE_EXPIRED");
        assertThat(body("code-burned").get("reason").asText()).isEqualTo("CODE_BURNED");
        assertThat(body("code-format").get("reason").asText()).isEqualTo("CODE_FORMAT");
        assertThat(body("restart").get("reason").asText()).isEqualTo("SIGN_IN_RESTART");
        assertThat(body("verified").get("next").asText()).isEqualTo(HELD);
        assertThat(body("state-pending-sent").get("pending").asBoolean()).isTrue();
        assertThat(body("state-pending-sent").get("sent").asBoolean()).isTrue();
        assertThat(body("state-pending-not-sent").get("pending").asBoolean()).isTrue();
        assertThat(body("state-pending-not-sent").get("sent").asBoolean()).isFalse();
        assertThat(body("state-none").get("pending").asBoolean()).isFalse();
        assertThat(body("next-held").get("next").asText()).isEqualTo(HELD);
        assertThat(body("next-hostile").get("next").asText()).isEqualTo(LoginCookie.HOME);
        List<String> verified = new java.util.ArrayList<>();
        body("verified").fieldNames().forEachRemaining(verified::add);
        assertThat(verified).as("the verified answer's fields: the place, never the session token (that is the cookie)").containsExactly("next");
    }
}
