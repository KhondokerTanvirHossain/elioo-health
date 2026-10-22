package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.domain.AuditEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W6-fix (ruling R1, 2026-10-22): both value-sheet routes, the HTML page families open from WhatsApp
 * ({@code /app/documents/{id}/values/{obs}}) and the JSON one ({@code /app/api/documents/{id}/values/{obs}}), go through
 * one {@code ResultUseCase.valueSheet} read, so the two rules hold on both, in both directions:
 * <ol>
 *   <li>a paper that is not DONE (NEEDS_RETAKE, FAILED, still being read) opens no sheet: 404 and no DOCUMENT_VIEW; a
 *       DONE paper's value opens one, with exactly one view;</li>
 *   <li>"this is the first time" is said only when no earlier reading of the marker exists in any unit: a marker read
 *       before only in another unit draws no history line at all; a marker never read before draws the first-time line.</li>
 * </ol>
 * Over the real module and Testcontainers Postgres (the views are counted in {@code baymax.audit_event} itself, and the
 * history is the real {@code readingsOf} scan). The HTML is asserted on its {@code #earlier} block, never on a
 * substring of a page full of UUIDs; the JSON on its field. Names and numbers are fictional.
 */
@Testcontainers
class ValueSheetGatesAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final AtomicInteger NUMBERS = new AtomicInteger(800);

    private final ObjectMapper json = new ObjectMapper();
    private final UiCopy copy = new UiCopy();

    /** Ma's paper with one stored creatinine row; the observation's id; the owner's session. */
    record Sheet(String owner, UUID paper, UUID observation, PatientProfile patient) {
    }

    private static Sheet donePaperWithCreatinine(org.springframework.context.ApplicationContext context, String unit) throws Exception {
        FamilyAccount owner = AppApiContext.family(context, number(), "Tanvir");
        PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
        UUID paper = AppApiContext.paper(context, POSTGRES, ma, "routine");
        AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-10-12' WHERE id = '" + paper + "'");
        return new Sheet(AppApiContext.session(context, owner.id()), paper, creatinine(ma, paper, "1.1", unit), ma);
    }

    private static String number() {
        return "+8801700000" + NUMBERS.getAndIncrement();
    }

    /** One creatinine row (canonical, so it has a history) on the paper, under its own crop; the id the database gave it. */
    private static UUID creatinine(PatientProfile patient, UUID document, String value, String unit) throws Exception {
        return UUID.fromString(scalar("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, ref_low,"
                + " ref_high, crop_key, observed_at) VALUES ('" + patient.id() + "','" + document + "','Creatinine','creatinine','" + value
                + "','" + unit + "',NULL,NULL,'" + patient.familyId() + "/" + patient.id() + "/" + document + "/crop-1.jpg',now()) RETURNING id"));
    }

    // ---- rule 1: a paper that is not DONE opens no sheet --------------------------------------------------------

    /**
     * A NEEDS_RETAKE or FAILED paper's stored row, and a row of a paper still being read, open no sheet on either route,
     * and no view is recorded for the probe. The paper's own JSON never shows such a paper's rows, so neither may a sheet.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NEEDS_RETAKE", "FAILED", "PROCESSING"})
    void aPaperThatIsNotDoneOpensNoSheetOnEitherRoute(String status) {
        AppApiContext.runner(POSTGRES).run(context -> {
            Sheet s = donePaperWithCreatinine(context, "mg/dL");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET status = '" + status + "'"
                    + ("NEEDS_RETAKE".equals(status) ? ", status_reason_code = 'low_overall'" : "") + " WHERE id = '" + s.paper() + "'");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> html = html(c, s);
            assertThat(html.getStatus().value()).as("the HTML sheet of a " + status + " paper's stored row").isEqualTo(404);
            assertThat(views(s.paper())).as("DOCUMENT_VIEW rows after the HTML sheet of a " + status + " paper").isZero();

            EntityExchangeResult<byte[]> api = api(c, s);
            assertThat(api.getStatus().value()).as("the JSON sheet of a " + status + " paper's stored row").isEqualTo(404);
            assertThat(json.readTree(api.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
            assertThat(views(s.paper())).as("DOCUMENT_VIEW rows after both sheets of a " + status + " paper").isZero();
        });
    }

    /** The other direction: the same row on a DONE paper opens its sheet on each route, each recording exactly one view. */
    @Test
    void aDonePapersValueOpensOneSheetOnEachRoute() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Sheet s = donePaperWithCreatinine(context, "mg/dL");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> html = html(c, s);
            assertThat(html.getStatus().value()).as("the HTML sheet of a DONE paper's value").isEqualTo(200);
            assertThat(body(html)).as("the HTML sheet is the sheet").contains("id=\"sheet-title\"");
            assertThat(views(s.paper())).as("DOCUMENT_VIEW rows after one HTML sheet").isEqualTo(1);

            EntityExchangeResult<byte[]> api = api(c, s);
            assertThat(api.getStatus().value()).as("the JSON sheet of a DONE paper's value").isEqualTo(200);
            assertThat(json.readTree(api.getResponseBody()).path("value").path("id").asText()).isEqualTo(s.observation().toString());
            assertThat(views(s.paper())).as("DOCUMENT_VIEW rows after one HTML and one JSON sheet").isEqualTo(2);
        });
    }

    // ---- rule 2: "the first time" only when the marker was never read, in any unit ------------------------------

    /**
     * The dangerous direction: creatinine read last month in µmol/L, now in mg/dL. There is no earlier reading in THIS
     * unit, but "this is the first time" would be false, so neither route draws a history line: the HTML has no
     * {@code #earlier} block, the JSON's {@code earlier_line_key} is null.
     */
    @Test
    void aMarkerReadBeforeOnlyInAnotherUnitIsNeverTheFirstTime() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Sheet s = donePaperWithCreatinine(context, "mg/dL");
            UUID older = AppApiContext.paper(context, POSTGRES, s.patient(), "routine");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-09-02' WHERE id = '" + older + "'");
            creatinine(s.patient(), older, "97", "µmol/L");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> html = html(c, s);
            assertThat(html.getStatus().value()).as("the HTML sheet").isEqualTo(200);
            String page = body(html);
            assertThat(page).as("the HTML sheet is the sheet").contains("id=\"sheet-title\"");
            assertThat(page.contains("id=\"earlier\"")).as("an #earlier block on the HTML sheet of a marker read before in µmol/L")
                    .isFalse();
            assertThat(page).as("the first-time line on the HTML sheet of a marker read before in µmol/L")
                    .doesNotContain(copy.t(Lang.BN, "sheet.earlier.none"));

            JsonNode body = json.readTree(api(c, s).getResponseBody());
            assertThat(body.has("earlier_line_key")).as("$.earlier_line_key is present").isTrue();
            assertThat(body.path("earlier_line_key").isNull()).as("$.earlier_line_key of a marker read before in µmol/L").isTrue();
            assertThat(body.path("earlier").size()).as("$.earlier: no reading in mg/dL").isZero();
        });
    }

    /** The safe direction: a marker never read before, in any unit, is the first time on both routes. */
    @Test
    void aMarkerNeverReadBeforeIsTheFirstTimeOnBothRoutes() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Sheet s = donePaperWithCreatinine(context, "mg/dL");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();

            String earlier = section(body(html(c, s)), "earlier");
            assertThat(earlier).as("the HTML #earlier block of a marker never read").contains("<p>" + copy.t(Lang.BN, "sheet.earlier.none") + "</p>");

            JsonNode body = json.readTree(api(c, s).getResponseBody());
            assertThat(body.path("earlier_line_key").asText()).as("$.earlier_line_key of a marker never read").isEqualTo("sheet.earlier.none");
        });
    }

    /** Readings in this unit AND in another: both routes head the history with this unit, and list only this unit's. */
    @Test
    void aMarkerReadInBothUnitsIsHeadedWithThisUnit() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Sheet s = donePaperWithCreatinine(context, "mg/dL");
            UUID other = AppApiContext.paper(context, POSTGRES, s.patient(), "routine");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-09-02' WHERE id = '" + other + "'");
            creatinine(s.patient(), other, "97", "µmol/L");
            UUID same = AppApiContext.paper(context, POSTGRES, s.patient(), "routine");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-07-11' WHERE id = '" + same + "'");
            UUID sameObservation = creatinine(s.patient(), same, "1.0", "mg/dL");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();

            String earlier = section(body(html(c, s)), "earlier");
            assertThat(earlier).as("the HTML #earlier heading").contains("<h2>" + copy.t(Lang.BN, "sheet.earlier", "mg/dL") + "</h2>");
            assertThat(earlier).as("the HTML #earlier row of the mg/dL reading")
                    .contains("href=\"/app/documents/" + same + "/values/" + sameObservation + "?from=all\"");
            assertThat(earlier).as("the HTML #earlier rows never link the µmol/L paper").doesNotContain("/app/documents/" + other + "/");

            JsonNode body = json.readTree(api(c, s).getResponseBody());
            assertThat(body.path("earlier_line_key").asText()).isEqualTo("sheet.earlier");
            assertThat(body.path("earlier").size()).isEqualTo(1);
            assertThat(body.path("earlier").get(0).path("observation_id").asText()).isEqualTo(sameObservation.toString());
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static EntityExchangeResult<byte[]> html(WebTestClient c, Sheet s) {
        return c.get().uri("/app/documents/" + s.paper() + "/values/" + s.observation() + "?from=summary").cookie("baymax_session", s.owner())
                .cookie("baymax_lang", Lang.BN.code).exchange().expectBody().returnResult();
    }

    private static EntityExchangeResult<byte[]> api(WebTestClient c, Sheet s) {
        return c.get().uri("/app/api/documents/" + s.paper() + "/values/" + s.observation()).cookie("baymax_session", s.owner())
                .cookie("baymax_lang", Lang.BN.code).exchange().expectBody().returnResult();
    }

    private static String body(EntityExchangeResult<byte[]> r) {
        return new String(r.getResponseBody() == null ? new byte[0] : r.getResponseBody(), StandardCharsets.UTF_8);
    }

    /** The element whose id is {@code id}, whole (its opening tag to its matching close of the same name). */
    private static String section(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as("block #" + id).isNotNegative();
        int open = html.lastIndexOf('<', at);
        String tag = html.substring(open + 1, html.indexOf(' ', open));
        return html.substring(open, html.indexOf("</" + tag + ">", at) + tag.length() + 3);
    }

    private static long views(UUID paper) throws Exception {
        return AppApiContext.count(POSTGRES, "select count(*) from baymax.audit_event where kind = '" + AuditEvent.Kind.DOCUMENT_VIEW.dbValue()
                + "' and document_id = '" + paper + "'");
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }
}
