package com.elioo.baymax.web.adapter.in.api;

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
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W3-api: {@code GET /app/api/sign-in/next?next=<encoded>}, the place a signed-in visitor of the sign-in page is
 * sent on to, checked by Java ({@link LoginCookie#nextPage}) so Next never judges {@code next} itself. A session route:
 * with no session it is the guard's 401, as every session route.
 */
class SignInNextApiTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4500001");
    private static final String HELD = "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4500003";

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final WebTestClient client;

    SignInNextApiTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
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
                new PreferencesApiHandler(properties), B3Handlers.thread(), B3Handlers.ask(),
                guard)).build();
    }

    /** {@code next} sent as Next sends it: one query parameter, percent-encoded (the template variable is encoded strictly). */
    private EntityExchangeResult<byte[]> next(String raw, boolean session) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(b -> b.path("/app/api/sign-in/next").queryParam("next", "{n}").build(raw));
        if (session) {
            spec = spec.cookie("baymax_session", "tok");
        }
        return spec.exchange().expectBody().returnResult();
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    @Test
    void aHeldPlaceIsJavasCheckedPlace() {
        // the exact bytes the Next page sends: encodeURIComponent of the held path
        EntityExchangeResult<byte[]> r = client.get().uri(java.net.URI.create("/app/api/sign-in/next?next=" + HELD.replace("/", "%2F")))
                .cookie("baymax_session", "tok").exchange().expectBody().returnResult();
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("200 {\"next\":\"" + HELD + "\"}");
        assertThat(r.getResponseHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void noNextIsHome() {
        EntityExchangeResult<byte[]> r = client.get().uri("/app/api/sign-in/next").cookie("baymax_session", "tok")
                .exchange().expectBody().returnResult();
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("200 {\"next\":\"" + LoginCookie.HOME + "\"}");
    }

    /**
     * The hostile shapes are {@code SignInApiHandlerTest.aHostileHeldPlaceOpensHomeAndIsNeverHeld}'s own, read from its
     * annotation: one list for every place a held {@code next} is checked, so a shape added there is tried here too.
     */
    static Stream<String> hostile() throws NoSuchMethodException {
        String[] values = SignInApiHandlerTest.class.getDeclaredMethod("aHostileHeldPlaceOpensHomeAndIsNeverHeld", String.class)
                .getAnnotation(ValueSource.class).strings();
        assertThat(values).as("the hostile shapes read from SignInApiHandlerTest").isNotEmpty();
        return Stream.of(values);
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void aHostileNextIsHome(String hostile) {
        EntityExchangeResult<byte[]> r = next(hostile, true);
        assertThat(r.getStatus().value() + " " + text(r)).as("next=" + hostile).isEqualTo("200 {\"next\":\"" + LoginCookie.HOME + "\"}");
    }

    @Test
    void withoutASessionIs401() {
        EntityExchangeResult<byte[]> r = next(HELD, false);
        assertThat(r.getStatus().value() + " " + text(r)).isEqualTo("401 {\"reason\":\"UNAUTHENTICATED\"}");
    }
}
