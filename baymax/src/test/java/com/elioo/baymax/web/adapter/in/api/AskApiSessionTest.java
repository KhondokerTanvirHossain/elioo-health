package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHAT-api Review Focus 5: the session ends between typing and sending. The ask is a session route (never
 * {@code AppApiGuard.OPEN}): an expired session and a signed-out one are each 401 {@code UNAUTHENTICATED} and store no turn,
 * and a request without the CSRF header is 403 and stores none. Both directions: the same ask on a live session is
 * admitted and stores one, so each refusal is the session's or the header's, not the fixture's.
 */
@Testcontainers
class AskApiSessionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final Map<String, String> ASK = Map.of("question", "আম্মার বুকে ব্যথা");

    @Test
    void anAskWithoutASessionIs401AndStoresNoTurn() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000461", "Rahim");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String expired = "tok-" + UUID.randomUUID();
            Instant now = Instant.now();
            context.getBean(SessionStorePort.class)
                    .create(PhoneHasher.sha256(expired), family.id(), now.minus(Duration.ofDays(31)), now.minus(Duration.ofMinutes(1))).block();
            String signedOut = AppApiContext.session(context, family.id());
            context.getBean(WebAuthUseCase.class).logout(signedOut).block();
            String live = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String uri = "/app/api/patients/" + ma.id() + "/ask";

            for (String token : List.of(expired, signedOut)) {
                client.post().uri(uri).cookie("baymax_session", token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(ASK).exchange()
                        .expectStatus().isUnauthorized()
                        .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
            }
            client.post().uri(uri).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(ASK).exchange()
                    .expectStatus().isUnauthorized();
            assertThat(AppApiContext.count(POSTGRES, "SELECT count(*) FROM baymax.chat_turn WHERE patient_id = '" + ma.id() + "'"))
                    .as("turns stored by refused asks").isZero();

            client.post().uri(uri).cookie("baymax_session", live).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(ASK).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.kind").isEqualTo("emergency");
            assertThat(AppApiContext.count(POSTGRES, "SELECT count(*) FROM baymax.chat_turn WHERE patient_id = '" + ma.id() + "'"))
                    .as("the same ask on a live session").isEqualTo(1);
        });
    }

    @Test
    void anAskWithoutTheHeaderIs403AndStoresNoTurn() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000462", "Rahim");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String live = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String uri = "/app/api/patients/" + ma.id() + "/ask";

            client.post().uri(uri).cookie("baymax_session", live).header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(ASK).exchange()
                    .expectStatus().isForbidden().expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true);
            client.post().uri(uri).cookie("baymax_session", live).header("X-Medioo", "1").header("Origin", "https://evil.example")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(ASK).exchange()
                    .expectStatus().isForbidden().expectBody().json("{\"reason\":\"FORBIDDEN_ORIGIN\"}", true);
            assertThat(AppApiContext.count(POSTGRES, "SELECT count(*) FROM baymax.chat_turn WHERE patient_id = '" + ma.id() + "'")).isZero();
        });
    }
}
