package com.elioo.baymax.web.adapter.in.router;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.web.adapter.in.handler.AuthHandler;
import com.elioo.baymax.web.adapter.in.handler.TimelineApiHandler;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaymaxAuthRouterTest {

    private static final UUID FAMILY = UUID.randomUUID();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final WebTestClient client;

    BaymaxAuthRouterTest() {
        BaymaxProperties props = new BaymaxProperties();
        SessionAuthFilter sessions = new SessionAuthFilter(auth, props);
        BaymaxAuthRouter router = new BaymaxAuthRouter();
        client = WebTestClient.bindToRouterFunction(
                router.baymaxAuthRoutes(new AuthHandler(auth, sessions), new ErrorResponseFilter())
                        .and(router.baymaxTimelineRoutes(new TimelineApiHandler(timeline), sessions, new ErrorResponseFilter())))
                .build();
    }

    @Test
    void requestAlwaysAnswers200WithTheSameBody() {
        when(auth.requestCode(anyString())).thenReturn(Mono.just("abc"));
        for (String number : List.of("+8801711111111", "not a number", "")) {
            client.post().uri("/api/v1/baymax/auth/request").bodyValue(Map.of("whatsapp_number", number))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("ok").jsonPath("$.message").exists();
        }
    }

    @Test
    void verifySetsAnHttpOnlySecureLaxCookie() {
        Instant now = Instant.now();
        WebSession session = new WebSession(UUID.randomUUID(), FAMILY, now, now, now.plusSeconds(86400 * 30));
        when(auth.verifyNumber("+8801711111111", "123456")).thenReturn(Mono.just(new IssuedSession("tok-123", session)));
        client.post().uri("/api/v1/baymax/auth/verify").bodyValue(Map.of("whatsapp_number", "+8801711111111", "code", "123456"))
                .exchange().expectStatus().isOk()
                .expectHeader().valueMatches("Set-Cookie", "baymax_session=tok-123;.*HttpOnly.*")
                .expectHeader().valueMatches("Set-Cookie", ".*Secure.*")
                .expectHeader().valueMatches("Set-Cookie", ".*SameSite=Lax.*")
                .expectBody().jsonPath("$.family_id").isEqualTo(FAMILY.toString());
    }

    @Test
    void aWrongCodeIs401WithNoCookie() {
        when(auth.verifyNumber(anyString(), anyString())).thenReturn(Mono.error(BaymaxException.unauthorized("invalid_code", "no")));
        client.post().uri("/api/v1/baymax/auth/verify").bodyValue(Map.of("whatsapp_number", "+8801711111111", "code", "000000"))
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().jsonPath("$.reason").isEqualTo("invalid_code");
    }

    @Test
    void withoutASessionCookieMeIs401AndNothingIsRead() {
        client.get().uri("/api/v1/baymax/me").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.reason").isEqualTo("no_session");
        verify(timeline, never()).me(any());
    }

    @Test
    void withALiveSessionMeIsScopedToThatFamily() {
        Instant now = Instant.now();
        when(auth.authenticate("tok-123")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, now, now, now.plusSeconds(60))));
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, "+8801711111111", "Owner", FamilyAccount.Plan.FREE, now, now), List.of())));
        client.get().uri("/api/v1/baymax/me").cookie("baymax_session", "tok-123").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.family.id").isEqualTo(FAMILY.toString())
                // the number is not in the response either
                .jsonPath("$.family.whatsapp_number").doesNotExist();
        verify(timeline).me(eq(FAMILY));
    }
}
