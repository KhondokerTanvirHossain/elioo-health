package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /app/api/documents/{id}/status} (plan B4c): the reading screen polls it every few seconds, so it must write
 * nothing — an audit row per poll would bury the real views in noise. The count is asserted both ways in one test: five
 * polls add nothing, and one open of the paper's page then adds exactly one, so the query demonstrably sees writes.
 */
@Testcontainers
class PaperStatusApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void pollingTheStatusWritesNoAudit() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000461", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "routine");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            long before = audits(paper);
            for (int i = 0; i < 5; i++) {
                client.get().uri("/app/api/documents/" + paper + "/status").cookie("baymax_session", token).exchange()
                        .expectStatus().isOk()
                        .expectHeader().valueEquals("Cache-Control", "no-store")
                        .expectBody().json("{\"status\":\"DONE\"}", true);
            }
            assertThat(audits(paper) - before).as("audit rows written by five status polls").isEqualTo(0);

            client.get().uri("/app/documents/" + paper).cookie("baymax_session", token).exchange().expectStatus().isOk();
            assertThat(audits(paper) - before).as("audit rows after one open of the paper's page (the query sees writes)")
                    .isEqualTo(1);
        });
    }

    @Test
    void anotherFamilysStatusIsNotFound() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount owner = AppApiContext.family(context, "+8801700000462", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "routine");
            FamilyAccount other = AppApiContext.family(context, "+8801700000463", "Karim");
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.get().uri("/app/api/documents/" + paper + "/status").cookie("baymax_session", AppApiContext.session(context, other.id()))
                    .exchange().expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
            // the owner's own poll of the same id answers, so the 404 above is the family's, not a missing route
            client.get().uri("/app/api/documents/" + paper + "/status").cookie("baymax_session", AppApiContext.session(context, owner.id()))
                    .exchange().expectStatus().isOk().expectBody().json("{\"status\":\"DONE\"}", true);
            client.get().uri("/app/api/documents/not-a-uuid/status").cookie("baymax_session", AppApiContext.session(context, owner.id()))
                    .exchange().expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
        });
    }

    private static long audits(UUID document) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from baymax.audit_event where document_id = '" + document + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
