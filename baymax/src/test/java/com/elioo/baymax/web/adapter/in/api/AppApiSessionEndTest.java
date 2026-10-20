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

/**
 * Review Focus 4, the Java half: a session that ends while a page is open. The cookie is still in the browser, so
 * "no cookie" is not the case under test — a real row that has expired, and one that was signed out, are sent through
 * the real store ({@code findLive} with now). Each must be the same 401 {@code UNAUTHENTICATED} the client turns into
 * sign-in with {@code next=}, never a 303 and never a cached answer, for a read and for a mutation that is otherwise
 * well-formed (so the 401 is not a CSRF refusal in disguise).
 */
@Testcontainers
class AppApiSessionEndTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void anExpiredAndASignedOutSessionAreEach401WithTheReasonOnAReadAndAMutation() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000451", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "আম্মা");

            String expired = "tok-" + UUID.randomUUID();
            Instant now = Instant.now();
            context.getBean(SessionStorePort.class)
                    .create(PhoneHasher.sha256(expired), family.id(), now.minus(Duration.ofDays(31)), now.minus(Duration.ofMinutes(1))).block();

            String signedOut = AppApiContext.session(context, family.id());
            String live = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            // both directions: before sign-out the token works, so the 401 below is the sign-out's and not the fixture's
            client.get().uri("/app/api/me").cookie("baymax_session", signedOut).exchange().expectStatus().isOk();
            context.getBean(WebAuthUseCase.class).logout(signedOut).block();

            for (String token : List.of(expired, signedOut)) {
                client.get().uri("/app/api/me").cookie("baymax_session", token).exchange()
                        .expectStatus().isUnauthorized()
                        .expectHeader().doesNotExist("Location")
                        .expectHeader().valueEquals("Cache-Control", "no-store")
                        .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
                client.put().uri("/app/api/active-patient").cookie("baymax_session", token)
                        .header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", ma.id().toString()))
                        .exchange()
                        .expectStatus().isUnauthorized()
                        .expectHeader().doesNotExist("Location")
                        .expectHeader().doesNotExist("Set-Cookie")
                        .expectHeader().valueEquals("Cache-Control", "no-store")
                        .expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
            }
            // the same request on a live session is admitted, so the refusals above are the session's
            client.put().uri("/app/api/active-patient").cookie("baymax_session", live)
                    .header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", ma.id().toString()))
                    .exchange().expectStatus().isNoContent();
        });
    }
}
