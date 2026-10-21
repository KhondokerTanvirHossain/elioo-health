package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.elioo.baymax.web.adapter.in.ui.Dom;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.service.UploadService;
import com.elioo.baymax.web.domain.AttentionItem;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan D1, end to end on Postgres and MinIO: the retake page's form names the paper its photo replaces, the upload links
 * the new paper to it, and the old paper's retake card leaves the চ্যাট stack; the FAILED page's resend names nothing and
 * still uploads; a replayed press links once; and the database itself refuses a link across patients (the retake_replaces trigger).
 * Everything is real except OCR and the model, stubbed to read every new photo as the painted NOW page.
 */
@Testcontainers
class RetakeLinkAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private record Family(PatientProfile patient, String session, WebTestClient client) {
    }

    private static Family family(ApplicationContext context, String number) {
        FamilyAccount family = AppApiContext.family(context, number, "Rahim");
        PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
        WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();
        return new Family(ma, AppApiContext.session(context, family.id()), client);
    }

    /** A paper of {@code patient} in {@code status}, uploaded now, as the pipeline leaves it (a retake with its F5 code). */
    private static UUID paper(ApplicationContext context, PatientProfile patient, String status) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class)
                .create(Document.received(patient.id(), patient.familyId(), 1, Instant.now())).block().id();
        AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET status = '" + status + "'"
                + (status.equals("NEEDS_RETAKE") ? ", status_reason_code = 'low_overall', status_reason = 'developer prose'" : "")
                + " WHERE id = '" + id + "'");
        AppApiContext.storedPage(POSTGRES, patient, id);
        return id;
    }

    /** The form with this id on the paper's page, as a browser would read it: its action and its hidden fields. */
    private static Map<String, String> form(Family f, UUID document, String formId) {
        String html = f.client().get().uri("/app/documents/" + document).cookie("baymax_session", f.session()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        Dom dom = Dom.parse(html);
        Dom.Element form = dom.all.stream().filter(e -> e.tag().equals("form") && formId.equals(e.attrs().get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no form #" + formId + " on the page of " + document));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("@action", form.attrs().get("action"));
        dom.all.stream().filter(e -> e.tag().equals("input") && "hidden".equals(e.attrs().get("type")) && inside(e, form))
                .forEach(e -> fields.put(e.attrs().get("name"), e.attrs().get("value")));
        return fields;
    }

    private static boolean inside(Dom.Element e, Dom.Element ancestor) {
        for (Dom.Element p = e.parent(); p != null; p = p.parent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Posts the form's hidden fields and a fresh photo to its action; the redirect's location. */
    private static String post(Family f, Map<String, String> form) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        form.forEach((name, value) -> {
            if (!name.startsWith("@")) {
                body.part(name, value);
            }
        });
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        EntityExchangeResult<byte[]> r = f.client().post().uri(form.get("@action")).cookie("baymax_session", f.session())
                .body(BodyInserters.fromMultipartData(body.build())).exchange().expectBody().returnResult();
        assertThat(r.getStatus().value()).as("the form's post answered " + text(r)).isEqualTo(303);
        String location = r.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).as("the upload's redirect").matches("/app/documents/[0-9a-f-]{36}");
        return location;
    }

    private static EntityExchangeResult<byte[]> apiPress(Family f, String key, UUID replaces) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", f.patient().id().toString());
        body.part("upload_key", key);
        body.part("replaces", replaces.toString());
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        return f.client().post().uri("/app/api/uploads").cookie("baymax_session", f.session()).header(AppApiGuard.HEADER, "1")
                .header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN).body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectBody().returnResult();
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    /** Polls the JSON status (30 s at most) until it reads {@code status}. */
    private static void waitFor(Family f, UUID document, String status) throws InterruptedException {
        String seen = null;
        for (int i = 0; i < 60; i++) {
            seen = f.client().get().uri("/app/api/documents/" + document + "/status").cookie("baymax_session", f.session()).exchange()
                    .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("status").toString();
            if (status.equals(seen)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the paper never reached " + status + " in 30 s; last seen " + seen);
    }

    private static List<UUID> retakeCards(ApplicationContext context, PatientProfile patient) {
        return context.getBean(AttentionUseCase.class).attention(patient.familyId(), patient.id()).block().stream()
                .filter(AttentionItem.Retake.class::isInstance).map(AttentionItem::documentId).toList();
    }

    private static UUID idOf(String path) {
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    @Test
    void theRetakePageSendsTheLinkAndTheOldCardLeaves() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000751");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            assertThat(retakeCards(context, f.patient())).as("the retake card, before the new photo").containsExactly(a);

            Map<String, String> form = form(f, a, "retake-form");
            assertThat(form).containsEntry("replaces", a.toString()).containsEntry("patient", f.patient().id().toString());
            UUID b = idOf(post(f, form));
            waitFor(f, b, "DONE");

            assertThat(scalar("SELECT replaces_document_id FROM baymax.document WHERE id = '" + b + "'")).isEqualTo(a.toString());
            assertThat(retakeCards(context, f.patient())).as("retake cards once the new photo is read").isEmpty();
        });
    }

    /** A FAILED paper is not a retake: its page's resend names nothing, and uploads (never 400 not_a_retake). */
    @Test
    void theFailedPageResendCarriesNoReplacesAndUploads() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000752");
            UUID failed = paper(context, f.patient(), "FAILED");
            Map<String, String> form = form(f, failed, "failed-form");
            assertThat(form).containsKey("patient").doesNotContainKey("replaces");
            UUID b = idOf(post(f, form));
            assertThat(b).isNotEqualTo(failed);
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "'")).isEqualTo("2");
            assertThat(scalar("SELECT (replaces_document_id IS NULL)::text FROM baymax.document WHERE id = '" + b + "'")).isEqualTo("true");
        });
    }

    /**
     * The same JSON press twice (a double tap, a resend) with {@code replaces}: one new paper, linked once, the retry
     * answered with the same paper (D3), and no link failure logged — the second identical UPDATE is a no-op.
     */
    @Test
    void aReplayedPressWithReplacesLinksOnce() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000753");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.start();
            Logger upload = (Logger) LoggerFactory.getLogger(UploadService.class);
            upload.addAppender(events);
            try {
                String key = UUID.randomUUID().toString();
                EntityExchangeResult<byte[]> first = apiPress(f, key, a);
                EntityExchangeResult<byte[]> retry = apiPress(f, key, a);
                assertThat(first.getStatus().value()).as(text(first)).isEqualTo(200);
                assertThat(retry.getStatus().value()).as(text(retry)).isEqualTo(200);
                assertThat(firstId(retry)).as("the retry's paper").isEqualTo(firstId(first));
            } finally {
                upload.detachAppender(events);
            }
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "' AND id <> '" + a + "'"))
                    .as("one new paper").isEqualTo("1");
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE replaces_document_id = '" + a + "'")).as("linked once").isEqualTo("1");
            assertThat(events.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList()).as("WARN or above").isEmpty();
        });
    }

    /** Through the real guard: a press naming a finished paper is 400 {"reason":"NOT_A_RETAKE"}, and nothing is stored. */
    @Test
    void aPressNamingAFinishedPaperIsRefusedByName() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000754");
            UUID done = paper(context, f.patient(), "DONE");
            EntityExchangeResult<byte[]> r = apiPress(f, UUID.randomUUID().toString(), done);
            assertThat(r.getStatus().value()).isEqualTo(400);
            assertThat(text(r)).isEqualTo("{\"reason\":\"NOT_A_RETAKE\"}");
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "'")).isEqualTo("1");
        });
    }

    /** the retake_replaces trigger, below every writer: a retake replaces a paper of the same patient, never another's, never itself. */
    @Test
    void theDatabaseRefusesALinkAcrossPatients() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000755");
            PatientProfile abbu = AppApiContext.patient(context, f.patient().familyId(), "Abbu");
            UUID mine = paper(context, f.patient(), "DONE");
            UUID theirs = paper(context, abbu, "NEEDS_RETAKE");
            assertThatThrownBy(() -> AppApiContext.sql(POSTGRES,
                    "UPDATE baymax.document SET replaces_document_id = '" + theirs + "' WHERE id = '" + mine + "'"))
                    .hasMessageContaining("a retake replaces a paper of the same patient");
            assertThatThrownBy(() -> AppApiContext.sql(POSTGRES,
                    "UPDATE baymax.document SET replaces_document_id = id WHERE id = '" + mine + "'"))
                    .hasMessageContaining("a retake replaces a paper of the same patient");
            assertThat(scalar("SELECT (replaces_document_id IS NULL)::text FROM baymax.document WHERE id = '" + mine + "'")).isEqualTo("true");
        });
    }

    private static String firstId(EntityExchangeResult<byte[]> r) {
        Matcher m = Pattern.compile("\"documents\":\\[\\{\"id\":\"([0-9a-f-]{36})\"").matcher(text(r));
        assertThat(m.find()).as("documents[0].id in " + text(r)).isTrue();
        return m.group(1);
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }
}
