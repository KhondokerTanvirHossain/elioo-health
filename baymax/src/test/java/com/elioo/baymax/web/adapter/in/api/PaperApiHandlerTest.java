package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.web.domain.AuditEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuild plan B2 step 9: {@code GET /app/api/documents/{id}} end to end, over the real module and Testcontainers
 * Postgres: visibility decided in SQL before anything is recorded, exactly one DOCUMENT_VIEW per opening, and a parked
 * explanation's body never in the JSON. Explanation rows are inserted as composed and not sent ({@code sent_at} NULL,
 * V14). Numbers are fictional.
 */
@Testcontainers
class PaperApiHandlerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String PARKED = "PARKED-BODY-7731";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anotherFamilysPaperIsNotFoundAndRecordsNoView() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount owner = AppApiContext.family(context, "+8801700000311", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "now");
            FamilyAccount stranger = AppApiContext.family(context, "+8801700000312", "Karim");
            String token = AppApiContext.session(context, stranger.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> r = client.get().uri("/app/api/documents/" + paper).cookie("baymax_session", token)
                    .exchange().expectBody().returnResult();
            assertThat(r.getStatus().value()).as("another family's paper").isEqualTo(404);
            assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
            assertThat(views(paper)).as("DOCUMENT_VIEW rows for a paper this family cannot see").isZero();
        });
    }

    @Test
    void openingAPaperRecordsExactlyOneView() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount owner = AppApiContext.family(context, "+8801700000321", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "now");
            String token = AppApiContext.session(context, owner.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            JsonNode body = get(client, token, paper);
            assertThat(body.path("id").asText()).isEqualTo(paper.toString());
            assertThat(body.path("urgency").asText()).isEqualTo("now");
            assertThat(views(paper)).as("DOCUMENT_VIEW rows after one opening").isEqualTo(1);
        });
    }

    @Test
    void aParkedExplanationsBodyNeverReachesTheJson() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount owner = AppApiContext.family(context, "+8801700000331", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "now");
            AppApiContext.explanation(POSTGRES, ma, paper, "pending", PARKED);
            String token = AppApiContext.session(context, owner.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            JsonNode parked = get(client, token, paper);
            List<String> carrying = new ArrayList<>();
            Json.walk(parked, (path, node) -> {
                if (node.isTextual() && node.asText().contains(PARKED)) {
                    carrying.add(path);
                }
            });
            // the body first: a parked body anywhere in the tree is the defect, whatever the gate field says
            assertThat(carrying).as("scalars carrying the parked body").isEmpty();
            assertThat(parked.has("wording")).as("$.wording while PENDING").isFalse();
            assertThat(parked.path("gate").asText()).isEqualTo("PENDING");

            // the other direction: released, the same row's body is the wording
            AppApiContext.sql(POSTGRES, "UPDATE baymax.outbound_message SET gate_status = 'released' WHERE document_id = '" + paper + "'");
            JsonNode released = get(client, token, paper);
            assertThat(released.path("gate").asText()).isEqualTo("RELEASED");
            assertThat(released.path("wording").asText()).isEqualTo(PARKED);
        });
    }

    /**
     * D2 (review findings 3, 4, 21): the paper's date fields over real rows, through the entity mapping, ResultService
     * and PaperJson, with no fixture computing anything. Each row's model flag is set, so both directions of Q6 are on
     * the wire at once: the DONE printed date is marked; the retake (misread date kept in doc_date, uploaded 00:30 on
     * 1 November in Dhaka) and the undated paper are filed under their upload days and NOT marked. A mapping that lost
     * the generated columns fails every case here, not one.
     */
    @Test
    void thePapersDateIsReadFromItsRow() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount owner = AppApiContext.family(context, "+8801700000341", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
            UUID retake = context.getBean(com.elioo.baymax.extraction.application.port.out.DocumentRecordPort.class)
                    .create(com.elioo.baymax.extraction.domain.Document.received(ma.id(), ma.familyId(), 1, java.time.Instant.now())).block().id();
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET status = 'NEEDS_RETAKE', document_type = 'lab_report', "
                    + "status_reason_code = 'low_overall', status_reason = 'developer prose', "
                    + "doc_date = '2021-03-04', doc_date_uncertain = true, created_at = '2026-10-31T18:30:00Z' WHERE id = '" + retake + "'");
            UUID printed = AppApiContext.paper(context, POSTGRES, ma, "routine");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-07-11', doc_date_uncertain = true WHERE id = '" + printed + "'");
            UUID undated = AppApiContext.paper(context, POSTGRES, ma, "routine");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = NULL, doc_date_uncertain = true, "
                    + "created_at = '2026-10-18T19:30:00Z' WHERE id = '" + undated + "'");
            String token = AppApiContext.session(context, owner.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            assertDates(get(client, token, retake), "the retake", "2026-11-01", true, null, false);
            assertDates(get(client, token, printed), "the uncertain printed date", "2026-07-11", false, "2026-07-11", true);
            assertDates(get(client, token, undated), "the undated paper", "2026-10-19", true, null, false);
        });
    }

    private static void assertDates(JsonNode body, String what, String date, boolean uploadDay, String printedDate, boolean uncertain) {
        assertThat(body.path("date").asText()).as(what + " $.date").isEqualTo(date);
        assertThat(body.path("date_is_upload_day").isBoolean()).as(what + " $.date_is_upload_day is a boolean").isTrue();
        assertThat(body.path("date_is_upload_day").booleanValue()).as(what + " $.date_is_upload_day").isEqualTo(uploadDay);
        assertThat(body.has("printed_date")).as(what + " $.printed_date is present").isTrue();
        assertThat(body.path("printed_date").isNull() ? null : body.path("printed_date").asText()).as(what + " $.printed_date")
                .isEqualTo(printedDate);
        assertThat(body.path("date_uncertain").isBoolean()).as(what + " $.date_uncertain is a boolean").isTrue();
        assertThat(body.path("date_uncertain").booleanValue()).as(what + " $.date_uncertain").isEqualTo(uncertain);
    }

    private JsonNode get(WebTestClient client, String token, UUID paper) throws Exception {
        EntityExchangeResult<byte[]> r = client.get().uri("/app/api/documents/" + paper).cookie("baymax_session", token)
                .exchange().expectBody().returnResult();
        assertThat(r.getStatus().value()).as("the owner's GET").isEqualTo(200);
        return json.readTree(r.getResponseBody());
    }

    /**
     * The DOCUMENT_VIEW rows of this paper, by the kind as the adapter stores it; and every audit row of the paper is one,
     * so a route that recorded some other kind instead of (or beside) the view is named here, not counted as the view.
     */
    private static long views(UUID paper) throws Exception {
        String kind = AuditEvent.Kind.DOCUMENT_VIEW.dbValue();
        long views = AppApiContext.count(POSTGRES, "select count(*) from baymax.audit_event where document_id = '" + paper
                + "' and kind = '" + kind + "'");
        long all = AppApiContext.count(POSTGRES, "select count(*) from baymax.audit_event where document_id = '" + paper + "'");
        assertThat(all).as("audit rows of this paper that are not " + kind).isEqualTo(views);
        return views;
    }
}
