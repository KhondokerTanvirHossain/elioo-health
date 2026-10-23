package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.elioo.baymax.web.adapter.in.ui.Dom;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
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
import java.util.ArrayList;
import java.util.Arrays;
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
 * still uploads; a replayed press links once; a stale {@code replaces} (its paper deleted, or no longer a retake) uploads
 * unlinked on both routes (PO ruling 2026-10-22, DR-46/DR-47), as does one naming another patient's or another family's
 * paper (DR-47); and the database itself refuses a link across patients (the retake_replaces trigger).
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
        return location(send(f, form));
    }

    /** Posts the form's hidden fields and a fresh photo to its action, and asserts nothing: the answer as it came. */
    private static EntityExchangeResult<byte[]> send(Family f, Map<String, String> form) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        form.forEach((name, value) -> {
            if (!name.startsWith("@")) {
                body.part(name, value);
            }
        });
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        return f.client().post().uri(form.get("@action")).cookie("baymax_session", f.session())
                .body(BodyInserters.fromMultipartData(body.build())).exchange().expectBody().returnResult();
    }

    /** The redirect of a form's post that uploaded: 303 to the new paper's page. */
    private static String location(EntityExchangeResult<byte[]> r) {
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
     * The same JSON press twice (a double tap, a resend) with {@code replaces}: one new paper, the retry answered with the
     * same paper (D3), and the retry links it again. Between the two presses the link is cleared, which is what a first
     * press that died after its document and before its link leaves behind: only a retry that relinks a replay's
     * documents puts it back (a retry that skipped them would leave the stale card for 30 days). No WARN either way.
     */
    @Test
    void aReplayedPressWithReplacesLinksAgainWhenTheFirstPressDiedBeforeItsLink() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000753");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.start();
            Logger upload = (Logger) LoggerFactory.getLogger(UploadService.class);
            upload.addAppender(events);
            String b;
            try {
                String key = UUID.randomUUID().toString();
                EntityExchangeResult<byte[]> first = apiPress(f, key, a);
                assertThat(first.getStatus().value()).as(text(first)).isEqualTo(200);
                b = firstId(first);
                // the first press died between its document and its link
                AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET replaces_document_id = NULL WHERE id = '" + b + "'");
                EntityExchangeResult<byte[]> retry = apiPress(f, key, a);
                assertThat(retry.getStatus().value()).as(text(retry)).isEqualTo(200);
                assertThat(firstId(retry)).as("the retry's paper").isEqualTo(b);
            } finally {
                upload.detachAppender(events);
            }
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "' AND id <> '" + a + "'"))
                    .as("one new paper").isEqualTo("1");
            assertThat(scalar("SELECT replaces_document_id::text FROM baymax.document WHERE id = '" + b + "'"))
                    .as("the replay relinked the paper the first press left unlinked").isEqualTo(a.toString());
            assertThat(LogCapture.events(events).stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList()).as("WARN or above").isEmpty();
        });
    }

    /**
     * First link wins. A replay of the same press (same key, same photo) naming a DIFFERENT retake of the same patient
     * passes every check (A2 is this patient's NEEDS_RETAKE paper), but must not move the link: moved, A's stale card
     * would come back and A2's card would leave the stack although no photo of A2 was ever sent, hiding a paper the
     * family still owes.
     */
    @Test
    void aReplayNamingAnotherRetakeLeavesTheFirstLinkInPlace() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000756");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID a2 = paper(context, f.patient(), "NEEDS_RETAKE");
            String key = UUID.randomUUID().toString();
            EntityExchangeResult<byte[]> first = apiPress(f, key, a);
            assertThat(first.getStatus().value()).as(text(first)).isEqualTo(200);
            EntityExchangeResult<byte[]> replay = apiPress(f, key, a2);
            assertThat(replay.getStatus().value()).as(text(replay)).isEqualTo(200);
            String b = firstId(first);
            assertThat(firstId(replay)).as("the replay's paper").isEqualTo(b);

            assertThat(scalar("SELECT replaces_document_id::text FROM baymax.document WHERE id = '" + b + "'"))
                    .as("the first link stands").isEqualTo(a.toString());
            assertThat(retakeCards(context, f.patient())).as("A2 is still owed a photo; A is not").containsExactly(a2);
        });
    }

    /**
     * The FAILED page of a paper that was itself sent to replace a retake (A needs a retake, B was sent from A's page, B
     * FAILED): the resend from B's page carries B's own link, so the photo that finally reads clears A's card too. Without
     * it the new photo C is unlinked and A's card stays for 30 days after C is read: the same stale duplicate, on the one
     * path the retake page does not cover.
     */
    @Test
    void aResendFromAFailedRetakeReplacesTheRetakeItWasSentFor() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000757");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID b = paper(context, f.patient(), "FAILED");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET created_at = created_at - interval '1 hour' WHERE id = '" + a + "'");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET replaces_document_id = '" + a + "' WHERE id = '" + b + "'");
            assertThat(retakeCards(context, f.patient())).as("B failed: A is still owed a photo").containsExactly(a);

            Map<String, String> form = form(f, b, "failed-form");
            assertThat(form).containsEntry("replaces", a.toString());
            UUID c = idOf(post(f, form));
            waitFor(f, c, "DONE");

            assertThat(scalar("SELECT replaces_document_id::text FROM baymax.document WHERE id = '" + c + "'")).isEqualTo(a.toString());
            assertThat(retakeCards(context, f.patient())).as("retake cards once the resend is read").isEmpty();
        });
    }

    /**
     * Every event logged at INFO or above while {@code body} runs, from every logger: the stale path's own line and the
     * two handlers' upload lines included, so a stale id logged anywhere on the press is caught, not only in UploadService.
     */
    private static List<ILoggingEvent> logged(ThrowingRunnable body) throws Exception {
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(events);
        try {
            body.run();
        } finally {
            root.detachAppender(events);
        }
        return LogCapture.events(events).stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * The guard itself, both directions, no containers: an INFO+ line with a null argument (any logger, any thread, in the
     * capture window) is read, not crashed on; {@code List.of} rejected it with a NullPointerException, which failed main
     * at b303f9a for no upload defect. A line whose argument is the id still fails.
     */
    @Test
    void noLineCarriesReadsANullArgumentAndStillCatchesTheId() {
        UUID id = UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(RetakeLinkAcceptanceTest.class);
        ILoggingEvent nullArgument = new LoggingEvent(Logger.FQCN, logger, Level.INFO, "press {} for {}", null, new Object[]{null, "x"});
        noLineCarries(List.of(nullArgument), id);
        ILoggingEvent carriesId = new LoggingEvent(Logger.FQCN, logger, Level.INFO, "press {}", null, new Object[]{null, id});
        assertThatThrownBy(() -> noLineCarries(List.of(carriesId), id)).isInstanceOf(AssertionError.class);
    }

    /** The stale paper's id in no INFO+ line: not in the formatted message, not among the arguments (by value). */
    private static void noLineCarries(List<ILoggingEvent> events, UUID... ids) {
        assertThat(events).as("lines were captured at all").isNotEmpty();
        for (UUID id : ids) {
            for (ILoggingEvent e : events) {
                assertThat(e.getFormattedMessage()).as("INFO+ line from %s", e.getLoggerName()).doesNotContain(id.toString());
                assertThat(e.getArgumentArray() == null ? List.of() : Arrays.asList(e.getArgumentArray()))
                        .as("arguments of an INFO+ line from %s", e.getLoggerName()).doesNotContain(id, id.toString());
            }
        }
    }

    /**
     * The drop path ran, and which one: each UploadService INFO line that says a press was uploaded unlinked, by its
     * label ({@code stale}: gone, never this family's, or no longer waiting; {@code another patient}: a visible paper of
     * another patient, DR-47). A link that failed leaves the same NULL column, so the column alone does not say which
     * rule ran; and no WARN.
     */
    private static void dropped(List<ILoggingEvent> events, String... labels) {
        assertThat(events.stream().filter(e -> e.getLevel() == Level.INFO && e.getLoggerName().equals(UploadService.class.getName())
                        && e.getFormattedMessage().contains("uploaded unlinked"))
                .map(e -> e.getFormattedMessage().contains("replaces is stale") ? "stale"
                        : e.getFormattedMessage().contains("another patient's paper") ? "another patient" : e.getFormattedMessage())
                .toList()).as("the drop lines, one per press").containsExactly(labels);
        assertThat(events.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).map(ILoggingEvent::getFormattedMessage).toList())
                .as("WARN or above").isEmpty();
    }

    /** The stale path ran, once per press. */
    private static void droppedAsStale(List<ILoggingEvent> events, int presses) {
        dropped(events, java.util.Collections.nCopies(presses, "stale").toArray(String[]::new));
    }

    /**
     * Rows per table naming this id in a column called {@code column} ({@code patient_id} or {@code family_id}), for every
     * table of the schema that has one: asked of information_schema, never listed by hand, so a table a later migration
     * adds is counted too. Guarded against emptiness: a query that found no table would compare two empty maps.
     */
    private static Map<String, Long> rowsNaming(String column, UUID id) throws Exception {
        Map<String, Long> rows = new java.util.TreeMap<>();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword())) {
            List<String> tables = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.columns "
                    + "WHERE table_schema = 'baymax' AND column_name = '" + column + "' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertThat(tables).as("information_schema returned the tables with a " + column + " column, else the sweep passes vacuously")
                    .contains("document").hasSizeGreaterThanOrEqualTo(2);
            for (String table : tables) {
                try (java.sql.PreparedStatement s = c.prepareStatement("SELECT count(*) FROM baymax." + table + " WHERE " + column + " = ?")) {
                    s.setObject(1, id);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        rows.put(table, rs.getLong(1));
                    }
                }
            }
        }
        return rows;
    }

    /** A sheet press as the HTML form sends it ({@code POST /app/add}): the chosen patient, a {@code replaces}, a back. */
    private static Map<String, String> sheetPress(PatientProfile patient, UUID replaces, String back) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("@action", "/app/add");
        form.put("upload_key", UUID.randomUUID().toString());
        form.put("patient", patient.id().toString());
        form.put("replaces", replaces.toString());
        form.put("back", back);
        return form;
    }

    private static String replacesOf(UUID document) throws Exception {
        return scalar("SELECT coalesce(replaces_document_id::text, 'none') FROM baymax.document WHERE id = '" + document + "'");
    }

    /**
     * PO ruling 2026-10-22 (D1 finding 16), the deleted half, on both routes: a retake page left open while its paper is
     * deleted, then sent ({@code POST /app/add}), and a JSON press naming a paper deleted since. Each photo is a normal new
     * paper, unlinked, never refused, and no INFO+ line names the deleted paper. The other direction, same family, same
     * run: a press naming a paper that still waits for its retake links.
     */
    @Test
    void aRetakeWhosePaperWasDeletedIsUploadedUnlinkedOnBothRoutes() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000761");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID a2 = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID waiting = paper(context, f.patient(), "NEEDS_RETAKE");
            Map<String, String> openPage = form(f, a, "retake-form");
            assertThat(openPage).containsEntry("replaces", a.toString());
            TimelineUseCase timeline = context.getBean(TimelineUseCase.class);
            timeline.deleteDocument(f.patient().familyId(), a).block();
            timeline.deleteDocument(f.patient().familyId(), a2).block();
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE id IN ('" + a + "','" + a2 + "')")).as("both deleted").isEqualTo("0");

            List<EntityExchangeResult<byte[]>> answers = new ArrayList<>();
            List<ILoggingEvent> events = logged(() -> {
                answers.add(send(f, openPage));
                answers.add(apiPress(f, UUID.randomUUID().toString(), a2));
            });
            // both routes' answers in one assertion, so a replay against the strict rule shows each route's refusal
            assertThat(answers.stream().map(r -> r.getStatus().value()).toList())
                    .as("[POST /app/add, POST /app/api/uploads] answered %s", answers.stream().map(r -> r.getStatus().value() + " " + (text(r).startsWith("{") ? text(r) : "(page)")).toList())
                    .containsExactly(303, 200);
            UUID[] made = {idOf(location(answers.get(0))), UUID.fromString(firstId(answers.get(1)))};
            assertThat(replacesOf(made[0])).as("the HTML retake form's photo").isEqualTo("none");
            assertThat(replacesOf(made[1])).as("the JSON press's photo").isEqualTo("none");
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "' AND id <> '" + waiting + "'"))
                    .as("two new papers, nothing else").isEqualTo("2");
            noLineCarries(events, a, a2);
            droppedAsStale(events, 2);

            // the other direction: a paper that still waits links, on the same route
            EntityExchangeResult<byte[]> valid = apiPress(f, UUID.randomUUID().toString(), waiting);
            assertThat(valid.getStatus().value()).as(text(valid)).isEqualTo(200);
            assertThat(replacesOf(UUID.fromString(firstId(valid)))).isEqualTo(waiting.toString());
        });
    }

    /**
     * The same ruling, the no-longer-a-retake half: the paper still exists but no longer waits for a retake (read since,
     * DONE). The retake page left open, then sent, and a JSON press naming a DONE paper: each a normal new paper,
     * unlinked, never 400 NOT_A_RETAKE. The other direction on the HTML route: a page whose paper still waits links.
     */
    @Test
    void aRetakeWhosePaperIsNoLongerARetakeIsUploadedUnlinkedOnBothRoutes() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000762");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID done = paper(context, f.patient(), "DONE");
            UUID waiting = paper(context, f.patient(), "NEEDS_RETAKE");
            Map<String, String> openPage = form(f, a, "retake-form");
            Map<String, String> waitingPage = form(f, waiting, "retake-form");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET status = 'DONE', status_reason_code = NULL, status_reason = NULL WHERE id = '" + a + "'");

            List<EntityExchangeResult<byte[]>> answers = new ArrayList<>();
            List<ILoggingEvent> events = logged(() -> {
                answers.add(send(f, openPage));
                answers.add(apiPress(f, UUID.randomUUID().toString(), done));
            });
            // both routes' answers in one assertion, so a replay against the strict rule shows each route's refusal
            assertThat(answers.stream().map(r -> r.getStatus().value()).toList())
                    .as("[POST /app/add, POST /app/api/uploads] answered %s", answers.stream().map(r -> r.getStatus().value() + " " + (text(r).startsWith("{") ? text(r) : "(page)")).toList())
                    .containsExactly(303, 200);
            UUID[] made = {idOf(location(answers.get(0))), UUID.fromString(firstId(answers.get(1)))};
            assertThat(replacesOf(made[0])).as("the HTML retake form's photo").isEqualTo("none");
            assertThat(replacesOf(made[1])).as("the JSON press's photo").isEqualTo("none");
            noLineCarries(events, a, done);
            droppedAsStale(events, 2);

            UUID linked = idOf(post(f, waitingPage));
            assertThat(replacesOf(linked)).as("the page whose paper still waits").isEqualTo(waiting.toString());
        });
    }

    /**
     * DR-47 (engineering default after the PO ruling of 2026-10-22, pending the PO's confirmation): a paper the family can
     * see that belongs to ANOTHER patient is dropped like a stale one, never refused. {@code patient} is what the family
     * chose; {@code replaces} is context the client carried (W7's sheet keeps its patient chooser while the URL names a
     * retake). On both routes the photo is a normal new paper of the chosen patient (Ma), unlinked, and nothing of Abbu's
     * changes: every table with a {@code patient_id} column, swept from the schema, holds the same rows for Abbu before
     * and after; his paper still waits, still links nothing, and its retake card is still in his চ্যাট stack.
     */
    @Test
    void aPressNamingAnotherPatientsPaperIsUploadedUnlinkedUnderTheChosenPatientOnBothRoutes() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000754");
            PatientProfile abbu = AppApiContext.patient(context, f.patient().familyId(), "Abbu");
            UUID abbusRetake = paper(context, abbu, "NEEDS_RETAKE");
            Map<String, Long> abbusRowsBefore = rowsNaming("patient_id", abbu.id());
            assertThat(abbusRowsBefore).as("the fixture gave Abbu rows to keep").containsEntry("document", 1L);

            List<EntityExchangeResult<byte[]>> answers = new ArrayList<>();
            List<ILoggingEvent> events = logged(() -> {
                answers.add(send(f, sheetPress(f.patient(), abbusRetake, "/app/documents/" + abbusRetake)));
                answers.add(apiPress(f, UUID.randomUUID().toString(), abbusRetake));
            });
            assertThat(answers.stream().map(r -> r.getStatus().value()).toList())
                    .as("[POST /app/add, POST /app/api/uploads] answered %s", answers.stream().map(r -> r.getStatus().value() + " " + (text(r).startsWith("{") ? text(r) : "(page)")).toList())
                    .containsExactly(303, 200);
            UUID[] made = {idOf(location(answers.get(0))), UUID.fromString(firstId(answers.get(1)))};
            for (UUID m : made) {
                assertThat(replacesOf(m)).as("the new photo links nothing").isEqualTo("none");
                assertThat(scalar("SELECT patient_id::text FROM baymax.document WHERE id = '" + m + "'")).as("filed under the chosen patient")
                        .isEqualTo(f.patient().id().toString());
            }
            assertThat(rowsNaming("patient_id", abbu.id())).as("every patient_id table, Abbu's rows").isEqualTo(abbusRowsBefore);
            assertThat(scalar("SELECT status || ' ' || coalesce(replaces_document_id::text, 'none') FROM baymax.document WHERE id = '" + abbusRetake + "'"))
                    .as("Abbu's paper").isEqualTo("NEEDS_RETAKE none");
            assertThat(retakeCards(context, abbu)).as("Abbu still owes a photo").containsExactly(abbusRetake);
            noLineCarries(events, abbusRetake);
            dropped(events, "another patient", "another patient");
        });
    }

    /**
     * The "never reveals whether an id exists" half of the ruling, on Postgres, where {@code findVisible}'s SQL decides
     * it: another FAMILY's paper that exists and waits for its retake. Pressed from family 1 on both routes, it is answered
     * exactly as a deleted paper is (303 / 200, unlinked, the STALE line, not the another-patient line, which would say
     * the id was found), and family 2's rows, swept from every {@code family_id} table, are untouched: its retake card is
     * still in its own চ্যাট stack.
     */
    @Test
    void aPressNamingAnotherFamilysRetakeIsAnsweredAsADeletedPaperIs() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000763");
            Family other = family(context, "+8801700000764");
            UUID theirs = paper(context, other.patient(), "NEEDS_RETAKE");
            Map<String, Long> theirRowsBefore = rowsNaming("family_id", other.patient().familyId());
            assertThat(theirRowsBefore).as("the fixture gave family 2 rows to keep").containsEntry("document", 1L);

            List<EntityExchangeResult<byte[]>> answers = new ArrayList<>();
            List<ILoggingEvent> events = logged(() -> {
                answers.add(send(f, sheetPress(f.patient(), theirs, "/app/timeline?patient=" + f.patient().id())));
                answers.add(apiPress(f, UUID.randomUUID().toString(), theirs));
            });
            assertThat(answers.stream().map(r -> r.getStatus().value()).toList())
                    .as("[POST /app/add, POST /app/api/uploads] answered %s", answers.stream().map(r -> r.getStatus().value() + " " + (text(r).startsWith("{") ? text(r) : "(page)")).toList())
                    .containsExactly(303, 200);
            UUID[] made = {idOf(location(answers.get(0))), UUID.fromString(firstId(answers.get(1)))};
            assertThat(replacesOf(made[0])).as("the HTML press's photo").isEqualTo("none");
            assertThat(replacesOf(made[1])).as("the JSON press's photo").isEqualTo("none");
            noLineCarries(events, theirs);
            droppedAsStale(events, 2);
            assertThat(rowsNaming("family_id", other.patient().familyId())).as("every family_id table, family 2's rows").isEqualTo(theirRowsBefore);
            assertThat(retakeCards(context, other.patient())).as("family 2 still owes its photo").containsExactly(theirs);
        });
    }

    /**
     * A stale retake form whose press is then refused for its file: the sheet comes back, and its close link (and the
     * form's own back) is the patient's papers, not {@code /app/documents/<deleted>}, which would land the family on a
     * not-found page for a paper they deleted themselves. Before the ruling this press was refused at admission, before
     * any file was read; now admission passes, so the later refusal is where the stale back would surface.
     */
    @Test
    void aStaleRetakeFormRefusedForItsFileClosesToThePatientsPapers() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000765");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            Map<String, String> openPage = form(f, a, "retake-form");
            assertThat(openPage).containsEntry("back", "/app/documents/" + a);
            context.getBean(TimelineUseCase.class).deleteDocument(f.patient().familyId(), a).block();

            MultipartBodyBuilder body = new MultipartBodyBuilder();
            openPage.forEach((name, value) -> {
                if (!name.startsWith("@")) {
                    body.part(name, value);
                }
            });
            body.part("file", "not a picture of anything".getBytes(StandardCharsets.UTF_8)).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
            EntityExchangeResult<byte[]> r = f.client().post().uri(openPage.get("@action")).cookie("baymax_session", f.session())
                    .body(BodyInserters.fromMultipartData(body.build())).exchange().expectBody().returnResult();
            assertThat(r.getStatus().value()).as("the refused press's sheet").isEqualTo(400);
            Dom dom = Dom.parse(text(r));
            String papers = "/app/timeline?patient=" + f.patient().id();
            assertThat(dom.all.stream().filter(e -> e.tag().equals("a") && e.attrs().containsKey("aria-label")
                            && e.attrs().getOrDefault("class", "").contains("icon-btn") && inside(e, dom.all.stream()
                            .filter(h -> h.attrs().getOrDefault("class", "").equals("sheet-h")).findFirst().orElseThrow()))
                    .map(e -> e.attrs().get("href")).toList()).as("the sheet's close link").containsExactly(papers);
            assertThat(dom.all.stream().filter(e -> e.tag().equals("input") && "back".equals(e.attrs().get("name")))
                    .map(e -> e.attrs().get("value")).toList()).as("the redrawn form's back").containsExactly(papers);
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + f.patient().id() + "'")).as("nothing stored").isEqualTo("0");
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

    /** The same rule on INSERT: a row written with a link already in it (a later writer, a backfill) meets the trigger too. */
    @Test
    void theDatabaseRefusesALinkAcrossPatientsOnInsert() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000758");
            PatientProfile abbu = AppApiContext.patient(context, f.patient().familyId(), "Abbu");
            UUID theirs = paper(context, abbu, "NEEDS_RETAKE");
            UUID row = UUID.randomUUID();
            assertThatThrownBy(() -> AppApiContext.sql(POSTGRES,
                    "INSERT INTO baymax.document (id, patient_id, family_id, status, page_count, replaces_document_id) VALUES ('"
                            + row + "','" + f.patient().id() + "','" + f.patient().familyId() + "','RECEIVED',1,'" + theirs + "')"))
                    .hasMessageContaining("a retake replaces a paper of the same patient");
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE id = '" + row + "'")).isEqualTo("0");
        });
    }

    /**
     * A retake replaces an OLDER paper. A replay of the press that made A, sent with {@code replaces} = B (A's own
     * replacement, NEEDS_RETAKE too), would otherwise link A to B while B links A: IN_FLIGHT then hides both and a paper
     * that still needs a photo leaves the stack. Ordering by upload rules a cycle out for every writer.
     */
    @Test
    void theDatabaseRefusesALinkToANewerPaper() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            Family f = family(context, "+8801700000759");
            UUID a = paper(context, f.patient(), "NEEDS_RETAKE");
            UUID b = paper(context, f.patient(), "NEEDS_RETAKE");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET created_at = created_at - interval '1 hour' WHERE id = '" + a + "'");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET replaces_document_id = '" + a + "' WHERE id = '" + b + "'");
            assertThatThrownBy(() -> AppApiContext.sql(POSTGRES,
                    "UPDATE baymax.document SET replaces_document_id = '" + b + "' WHERE id = '" + a + "'"))
                    .hasMessageContaining("a retake replaces an older paper");
            assertThat(scalar("SELECT (replaces_document_id IS NULL)::text FROM baymax.document WHERE id = '" + a + "'")).isEqualTo("true");
            assertThat(retakeCards(context, f.patient())).as("B is still owed a photo").containsExactly(b);
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
