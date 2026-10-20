package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 */
class AppApiGuardTest {

    static final String ORIGIN = "https://medioo.eliooo.org";
    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    private static final UUID MA = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200002");

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final AttentionUseCase attention = mock(AttentionUseCase.class);
    private final WebTestClient client;

    AppApiGuardTest() {
        BaymaxProperties properties = new BaymaxProperties();
        properties.getWeb().setPublicOrigin(ORIGIN);
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        when(attention.urgent(any(), any())).thenReturn(Mono.just(List.of()));
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, "+8801700000201", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW),
                List.of(new PatientSummary(new PatientProfile(MA, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW),
                        true, 0, null)))));
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, attention, properties), new AppApiGuard(sessions, properties))).build();
    }

    @Test
    void aReadWithoutASessionIs401WithTheReasonNeverARedirect() {
        client.get().uri("/app/api/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Location")
                .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
        verifyNoInteractions(timeline);
    }

    @Test
    void aMutationWithoutTheMediooHeaderIs403MissingHeader() {
        client.put().uri("/app/api/active-patient").cookie("baymax_session", "tok")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true);
    }

    @Test
    void aMutationFromAnotherOriginIs403ForbiddenOrigin() {
        client.put().uri("/app/api/active-patient").cookie("baymax_session", "tok")
                .header("X-Medioo", "1").header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().json("{\"reason\":\"FORBIDDEN_ORIGIN\"}", true);
    }

    @Test
    void aMutationWithAPlainTextBodyIs415() {
        client.put().uri("/app/api/active-patient").cookie("baymax_session", "tok")
                .header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.TEXT_PLAIN).bodyValue("{\"patient_id\":\"" + MA + "\"}")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true);
    }

    @Test
    void aMutationWithTheHeaderTheOriginAndJsonIsAccepted() {
        client.put().uri("/app/api/active-patient").cookie("baymax_session", "tok")
                .header("X-Medioo", "1").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueMatches("Set-Cookie", "baymax_patient=" + MA + ";.*");
    }

    /** "whenever an Origin header is sent": a same-origin fetch that sends none still needs the header and the type. */
    @Test
    void aMutationWithNoOriginHeaderIsAcceptedOnTheOtherTwoRules() {
        client.put().uri("/app/api/active-patient").cookie("baymax_session", "tok")
                .header("X-Medioo", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", MA.toString()))
                .exchange()
                .expectStatus().isNoContent();
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
                .expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
    }
}
