package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W5-api: reading the thread is not opening a paper. Each card is the paper's body, read through
 * {@code ResultUseCase.card}, which records no DOCUMENT_VIEW; only {@code GET /app/api/documents/{id}} does, once per
 * GET (master Review Focus 4, the Java half). Counted by column ({@code audit_event.kind}, V8), never by a log line.
 */
@Testcontainers
class ThreadApiAuditTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");

    private static long views(PatientProfile p) throws Exception {
        return FixedPapers.count(POSTGRES, "SELECT count(*) FROM baymax.audit_event WHERE kind = 'document_view' AND patient_id = '" + p.id() + "'");
    }

    @Test
    void readingTheThreadRecordsNoView() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "thread-audit";
            UUID f = FixedPapers.family(POSTGRES, FixedPapers.id(k, "family"), "+8801700000951", "Rahim");
            PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
            // both branches of ResultUseCase.card (review of #135, finding 1): three finished papers (its result branch)
            // and two still being read, RECEIVED and PROCESSING (its reading branch), so a view recorded by either fails
            List<UUID> papers = new ArrayList<>();
            List<String> statuses = List.of("DONE", "RECEIVED", "DONE", "PROCESSING", "DONE");
            for (int i = 0; i < statuses.size(); i++) {
                Instant at = Instant.parse("2026-10-01T04:00:00Z").plus(Duration.ofDays(i));
                boolean done = "DONE".equals(statuses.get(i));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper-" + i), ma, done ? "lab_report" : null, statuses.get(i),
                        done ? at.toString().substring(0, 10) : null, at, !done ? null : i == 0 ? "now" : "routine"));
            }
            String session = FixedPapers.session(context, f, NOW);
            long before = views(ma);

            client.get().uri("/app/api/patients/" + ma.id() + "/thread").cookie("baymax_session", session).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(5)
                    .jsonPath("$.items[?(@.card.status == 'RECEIVED')]").isNotEmpty()
                    .jsonPath("$.items[?(@.card.status == 'PROCESSING')]").isNotEmpty();
            assertThat(views(ma) - before).as("document_view rows a thread read of five papers (three DONE, one RECEIVED, one"
                    + " PROCESSING) added").isEqualTo(0);

            // the guard: the counter counts, on both branches. One finished paper's GET is one view, and so is one in-flight
            // paper's, so the zero above is not a query that sees nothing, nor a reading branch that could never record
            client.get().uri("/app/api/documents/" + papers.get(2)).cookie("baymax_session", session).exchange().expectStatus().isOk();
            assertThat(views(ma) - before).as("document_view rows after one GET /app/api/documents/{id} of a DONE paper").isEqualTo(1);
            client.get().uri("/app/api/documents/" + papers.get(3)).cookie("baymax_session", session).exchange().expectStatus().isOk();
            assertThat(views(ma) - before).as("document_view rows after one more GET, of the PROCESSING paper").isEqualTo(2);
        });
    }
}
