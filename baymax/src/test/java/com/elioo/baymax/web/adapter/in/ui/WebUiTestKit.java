package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds the web UI under test once. A constructor change touches this file and the class, never every test. */
final class WebUiTestKit {

    static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    static final String OWNER_NUMBER = "+8801700000000";
    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100001");
    static final UUID MA_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100002");
    static final UUID DOC_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100003");

    final WebAuthUseCase auth;
    final TimelineUseCase timeline = mock(TimelineUseCase.class);
    final NudgeOptOutUseCase optOut = mock(NudgeOptOutUseCase.class);
    final NudgeDataPort nudgeData = mock(NudgeDataPort.class);
    final BaymaxProperties properties;
    final UiCopy copy = new UiCopy();
    final SessionAuthFilter sessions;
    final RouterFunction<ServerResponse> router;
    final WebTestClient client;
    /** R1.1 only: the single handler, for the render tests. R1.2 replaces it with the per-screen handlers. */
    WebUiHandler ui;

    WebUiTestKit() {
        this(mock(WebAuthUseCase.class), new BaymaxProperties(), true);
    }

    /** A real auth service (SignInIsFreeAcceptanceTest) and its properties; {@code stubSession=false} leaves auth alone. */
    WebUiTestKit(WebAuthUseCase auth, BaymaxProperties properties, boolean stubSession) {
        this.auth = auth;
        this.properties = properties;
        if (stubSession) {
            when(auth.authenticate(anyString())).thenReturn(Mono.empty());
            when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(
                    UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100009"), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        }
        this.sessions = new SessionAuthFilter(auth, properties);
        this.router = buildRouter();
        this.client = WebTestClient.bindToRouterFunction(router).build();
    }

    /** R1.1: today's single handler. R1.2 replaces this body with the per-screen handlers; nothing else changes. */
    private RouterFunction<ServerResponse> buildRouter() {
        ui = new WebUiHandler(auth, timeline, sessions, copy, optOut, nudgeData, properties);
        return new BaymaxWebUiRouter().baymaxWebUiRoutes(ui, sessions);
    }

    PatientSummary ma(long documents) {
        return new PatientSummary(new PatientProfile(MA_ID, FAMILY, "আম্মা", 64, PatientProfile.Sex.FEMALE,
                List.of(), NOW, NOW), true, documents, documents == 0 ? null : NOW);
    }

    PatientSummary patient(String name, boolean owner, long documents) {
        UUID id = UUID.nameUUIDFromBytes(("patient:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new PatientSummary(new PatientProfile(id, FAMILY, name, 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW),
                owner, documents, documents == 0 ? null : NOW);
    }

    void familyWith(PatientSummary... patients) {
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, OWNER_NUMBER, "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW), List.of(patients))));
    }

    /** A signed-in GET, in a language; 200 expected. */
    String page(String path, String lang) {
        return client.get().uri(path).cookie("baymax_session", "tok").cookie("baymax_lang", lang)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** A GET with no session (landing, sign-in); 200 expected. */
    String open(String path, String lang) {
        return client.get().uri(path).cookie("baymax_lang", lang)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** The bytes the browser receives for an asset path, after asserting 200: what every CSS and weight check reads. */
    byte[] served(String path) {
        return client.get().uri(path).exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    String servedText(String path) {
        return new String(served(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
