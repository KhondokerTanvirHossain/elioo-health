package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.LoggedText;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan B1 step 1: the {@code /app/api} guard, over the router with a stub session. No session is a 401 with a reason
 * (never the HTML 303); a mutation needs {@code X-Medioo: 1}, an {@code Origin} equal to the public origin whenever one
 * is sent, and a JSON or multipart body; a read needs none of them. Each refusal is asserted by status AND reason, so a
 * 403 for the wrong rule cannot pass for the right one.
 *
 * <p>The 415 rule is asserted where nothing but the guard can produce it: on a route whose handler never reads the
 * body (the catch-all, whose own answer is a 404) and in front of a recording handler that must never run. A route that
 * decodes JSON answers 415 for text/plain on its own (Spring's decoder), so a test there cannot tell the guard ran.
 */
class AppApiGuardTest {

    static final String ORIGIN = "https://medioo.eliooo.org";
    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    private static final UUID MA = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200002");

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final AttentionUseCase attention = mock(AttentionUseCase.class);
    private final ResultUseCase results = mock(ResultUseCase.class);
    /** How many times the probe route's handler ran: the guard must refuse before it, never after. */
    private final AtomicInteger probed = new AtomicInteger();
    private final WebTestClient client;

    AppApiGuardTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        when(attention.urgent(any(), any())).thenReturn(Mono.just(List.of()));
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, "+8801700000201", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW),
                List.of(new PatientSummary(new PatientProfile(MA, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW),
                        true, 0, null)))));
        client = clientFor(ORIGIN);
    }

    private WebTestClient clientFor(String publicOrigin) {
        BaymaxProperties properties = new BaymaxProperties();
        properties.getWeb().setPublicOrigin(publicOrigin);
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        RouterFunction<ServerResponse> probes = RouterFunctions.route()
                .POST("/app/api/probe", request -> {
                    probed.incrementAndGet();
                    return ServerResponse.noContent().build();
                })
                .GET("/app/api/boom-iae", request -> {
                    throw new IllegalArgumentException("Unknown sex: রোকেয়া");
                })
                .GET("/app/api/boom-503", request -> Mono.error(
                        new BaymaxException(HttpStatus.SERVICE_UNAVAILABLE, "storage_down", "রোকেয়া's page")))
                .filter(guard)
                .build();
        return WebTestClient.bindToRouterFunction(probes.and(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, attention, properties), new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(mock(UploadUseCase.class), properties, new UiCopy()),
                new PaperStatusApiHandler(results),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                new HealthApiHandler(org.mockito.Mockito.mock(com.elioo.baymax.web.application.port.in.HealthUseCase.class)),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                guard))).build();
    }

    private WebTestClient.RequestBodySpec mutation(WebTestClient c, String uri) {
        return c.put().uri(uri).cookie("baymax_session", "tok");
    }

    @Test
    void aReadWithoutASessionIs401WithTheReasonNeverARedirect() {
        client.get().uri("/app/api/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Location")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
        verifyNoInteractions(timeline);
    }

    /** Plan B6: the health reads are behind the guard like every other route: no session, a 401 with the reason. */
    @Test
    void theHealthReadsWithoutASessionAre401() {
        for (String route : List.of("labs", "medicines")) {
            client.get().uri("/app/api/patients/" + MA + "/" + route).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().doesNotExist("Location")
                    .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
        }
    }

    @Test
    void aMutationWithoutTheMediooHeaderIs403MissingHeader() {
        mutation(client, "/app/api/active-patient")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true);
    }

    /** Only {@code X-Medioo: 1} passes: a header that is present with another value is the same refusal. */
    @Test
    void aMediooHeaderWithAnyValueButOneIs403MissingHeader() {
        for (String value : List.of("true", "0", "", "yes")) {
            mutation(client, "/app/api/active-patient").header("X-Medioo", value)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true);
        }
    }

    @Test
    void aMutationFromAnotherOriginIs403ForbiddenOrigin() {
        mutation(client, "/app/api/active-patient")
                .header("X-Medioo", "1").header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"FORBIDDEN_ORIGIN\"}", true);
    }

    /**
     * The guard's own 415, on a route whose handler never reads a body: the catch-all answers 404 by itself, so only
     * the guard can make this a 415. Missing type and a wildcard are refused the same way; multipart is admitted.
     */
    @Test
    void aMutationWithoutAJsonOrMultipartTypeIs415EvenWhereTheHandlerNeverReadsTheBody() {
        client.post().uri("/app/api/x").cookie("baymax_session", "tok").header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.TEXT_PLAIN).bodyValue("{\"patient_id\":\"" + MA + "\"}")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true);
        client.post().uri("/app/api/x").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true);
        client.delete().uri("/app/api/x").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true);
        // admitted: the catch-all's own answer, after the guard let it through
        client.post().uri("/app/api/x").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("note", "x");
        client.post().uri("/app/api/x").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
    }

    /** The refusal happens before the handler: a text/plain POST never reaches it, a JSON one does, exactly once. */
    @Test
    void aRefusedMutationNeverInvokesTheHandler() {
        client.post().uri("/app/api/probe").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .contentType(MediaType.TEXT_PLAIN).bodyValue("x")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true);
        client.post().uri("/app/api/probe").cookie("baymax_session", "tok")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
        assertThat(probed.get()).as("the handler behind a refused mutation").isZero();
        client.post().uri("/app/api/probe").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isNoContent();
        assertThat(probed.get()).as("the handler behind an admitted mutation").isEqualTo(1);
    }

    @Test
    void aMutationWithTheHeaderTheOriginAndJsonIsAccepted() {
        mutation(client, "/app/api/active-patient")
                .header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueMatches("Set-Cookie", "baymax_patient=" + MA + ";.*");
    }

    /** "whenever an Origin header is sent": a same-origin fetch that sends none still needs the header and the type. */
    @Test
    void aMutationWithNoOriginHeaderIsAcceptedOnTheOtherTwoRules() {
        mutation(client, "/app/api/active-patient")
                .header("X-Medioo", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isNoContent();
    }

    /** Blank configuration fails closed: even the production origin is refused, and a request with no Origin still passes. */
    @Test
    void aBlankPublicOriginRefusesEveryOriginButNotARequestThatSendsNone() {
        WebTestClient blank = clientFor("");
        mutation(blank, "/app/api/active-patient")
                .header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().json("{\"reason\":\"FORBIDDEN_ORIGIN\"}", true);
        mutation(blank, "/app/api/active-patient")
                .header("X-Medioo", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isNoContent();
    }

    /**
     * Wave 2 B4a: the sign-in pair is open — a visitor with no session reaches it through the CSRF rules — and opening
     * it opened nothing else: a read with no session is still the 401.
     */
    @Test
    void anOpenRouteNeedsNoSessionButAClosedOneStillDoes() {
        var open = client.post().uri("/app/api/sign-in/request").header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("whatsapp_number", "01700000123"))
                .exchange().expectBody(String.class).returnResult();
        if (open.getStatus().value() == 401) {
            org.assertj.core.api.Assertions.fail("expected not 401 but was 401 " + open.getResponseBody());
        }
        assertThat(open.getStatus().value()).as("the unconfirmed number, shown back").isEqualTo(200);
        assertThat(open.getResponseBody()).contains("\"number_display\"");
        client.get().uri("/app/api/me").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
    }

    /**
     * A probe router whose routes sit on the {@link AppApiGuard#OPEN} patterns, behind the real guard and nothing else,
     * so what the guard does on an open route is asserted apart from what the sign-in handler does there.
     */
    private WebTestClient openProbes(HandlerFunction<ServerResponse> request, HandlerFunction<ServerResponse> verify) {
        BaymaxProperties properties = new BaymaxProperties();
        properties.getWeb().setPublicOrigin(ORIGIN);
        AppApiGuard guard = new AppApiGuard(new SessionAuthFilter(auth, properties), properties);
        assertThat(AppApiGuard.OPEN).as("the probes stand on the open patterns")
                .containsExactlyInAnyOrder("/app/api/sign-in/request", "/app/api/sign-in/verify");
        return WebTestClient.bindToRouterFunction(RouterFunctions.route()
                .POST("/app/api/sign-in/request", request)
                .POST("/app/api/sign-in/verify", verify)
                .filter(guard)
                .build()).build();
    }

    private static WebTestClient.ResponseSpec openPost(WebTestClient c, String uri) {
        return c.post().uri(uri).header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
    }

    /**
     * The invariant, once, where every open route meets it: an open route never answers 401, whoever produced it — a
     * handler that raised a 401 error or one that returned a 401 response. 401 means "no session, go and sign in",
     * and these are the sign-in routes; the guard answers {@code SIGN_IN_RESTART} instead.
     */
    @Test
    void anOpenRouteNeverAnswers401WhoeverProducedIt() {
        WebTestClient c = openProbes(
                request -> Mono.error(BaymaxException.unauthorized("x", "y")),
                request -> ApiReason.UNAUTHENTICATED.respond());
        for (String uri : List.of("/app/api/sign-in/request", "/app/api/sign-in/verify")) {
            var r = openPost(c, uri).expectBody(String.class).returnResult();
            assertThat(r.getStatus().value() + " " + r.getResponseBody()).as(uri)
                    .isEqualTo("400 {\"reason\":\"SIGN_IN_RESTART\"}");
            assertThat(r.getResponseHeaders().getCacheControl()).as(uri + " Cache-Control").isEqualTo("no-store");
        }
    }

    /** An open route needs no session, but one that resolves is on the request for the handler, as on every route. */
    @Test
    void aSessionThatResolvesOnAnOpenRouteIsPutOnTheRequest() {
        HandlerFunction<ServerResponse> reads = request -> {
            WebSession s = SessionAuthFilter.session(request);
            return ServerResponse.ok().bodyValue(s == null ? "none" : s.familyId().toString());
        };
        WebTestClient c = openProbes(reads, reads);
        assertThat(c.post().uri("/app/api/sign-in/request").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody())
                .as("with a live session").isEqualTo(FAMILY.toString());
        assertThat(openPost(c, "/app/api/sign-in/request").expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody()).as("with none").isEqualTo("none");
    }

    @Test
    void aReadWithASessionNeedsNoMediooHeader() {
        String body = client.get().uri("/app/api/me").cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"active_patient_id\"");
    }

    @Test
    void anUnknownApiPathIsNotFoundWithTheReasonAfterTheSession() {
        client.get().uri("/app/api/no-such-thing").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
        client.get().uri("/app/api/no-such-thing").cookie("baymax_session", "tok").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
    }

    /**
     * A bare IllegalArgumentException is a server fault (an unknown stored enum value, a precondition), never the
     * client's: 500, no body, and exactly one ERROR line that names the class and not the message.
     */
    @Test
    void anIllegalArgumentFromAHandlerIsA500WithOneErrorLineNeverAnInvalidRequest() {
        List<ILoggingEvent> errors = errorsDuring(() -> client.get().uri("/app/api/boom-iae").cookie("baymax_session", "tok").exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().isEmpty());
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).startsWith("[baymax-api] unhandled error").contains("IllegalArgumentException");
        assertThat(LoggedText.of(errors.get(0))).doesNotContain("রোকেয়া");
    }

    /** A 5xx a handler raised on purpose (storage down) leaves an ERROR line with the class, so an incident has a start. */
    @Test
    void aServerErrorStatusFromAHandlerLeavesOneClassOnlyErrorLine() {
        List<ILoggingEvent> errors = errorsDuring(() -> client.get().uri("/app/api/boom-503").cookie("baymax_session", "tok").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store"));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).startsWith("[baymax-api]").contains("503").contains("BaymaxException");
        assertThat(LoggedText.of(errors.get(0))).doesNotContain("রোকেয়া").doesNotContain("storage_down");
    }

    private static List<ILoggingEvent> errorsDuring(Runnable requests) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppApiGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            requests.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
