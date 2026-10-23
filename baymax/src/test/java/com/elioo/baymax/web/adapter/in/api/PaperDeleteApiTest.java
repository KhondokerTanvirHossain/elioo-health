package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.testsupport.DeletionSweep;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.testsupport.PaintedPaper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a paper over {@code /app/api} (plan B4c): owner only, behind a confirm token, and afterwards nothing in the
 * database or the bucket still names it — asked of the schema and of the content, never of a list (DeletionSweep).
 * Everything is real (Postgres, MinIO, the router, the guard, the services) except OCR and the model, stubbed at their
 * ports, and the delivery channel, which records what it was handed.
 *
 * <p>The paper carries one value, one medicine and one follow-up, each with a needle no other text could contain by
 * chance, so the content scan cannot match by accident and cannot pass because a word was never stored.
 */
@Testcontainers
class PaperDeleteApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    /**
     * The paper's needles, unique to each test (the containers are shared by the class, so a needle another test's paper
     * also carries would make the content scan order-dependent): a value, a medicine and a follow-up instruction, each
     * long and of fixed length, so the spans below stay put.
     */
    private static final java.util.concurrent.atomic.AtomicInteger PAPERS = new java.util.concurrent.atomic.AtomicInteger();
    private String value;
    private String medicine;
    private String followUp;

    /** Synthetic (DR-36): 4.2 inside its printed 3.5–5.1, a routine paper; the follow-up is long past, so no rule fires on it. */
    private String reply() {
        return """
                {"document_type":"lab_report","document_date":"2026-10-12","facility":"Shapla Diagnostics",
                 "values":[{"name":"%s","value":"4.2","unit":"mmol/L","ref_low":"3.5","ref_high":"5.1",
                            "source_span":{"page":1,"start":0,"end":11}}],
                 "medicines":[{"name":"%s","dose_text":"10 mg","frequency_text":"1+0+0",
                               "source_span":{"page":1,"start":16,"end":29}}],
                 "follow_up":[{"instruction":"%s","due_date":"2026-01-05",
                               "source_span":{"page":1,"start":30,"end":52}}],
                 "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                                     "investigations_advised":[],"advice":[],"referral":null},
                 "confidence":{"overall":0.95,"values":0.95,"medicines":0.95,"follow_up":0.95,"clinical_context":1.0}}
                """.formatted(value, medicine, followUp);
    }

    private static final String EXTRACTION_LOGGER = "com.elioo.baymax.extraction";
    private ListAppender<ILoggingEvent> chain;

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    @BeforeEach
    void listen() {
        int n = PAPERS.incrementAndGet();
        assertThat(n).as("needles keep their length (and the spans their place): one base-36 digit per paper").isLessThan(36);
        char d = Character.forDigit(n, 36);
        value = "Kalium-884" + d;
        medicine = "Zorvalex-773" + d;
        followUp = "Return-for-review-551" + d;
        chain = new ListAppender<>();
        chain.start();
        ((Logger) LoggerFactory.getLogger(EXTRACTION_LOGGER)).addAppender(chain);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(EXTRACTION_LOGGER)).detachAppender(chain);
    }

    /** The stub OCR's words, left to right: the spans index into "Kalium-884n 4.2 Zorvalex-773n Return-for-review-551n". */
    ReactiveWebApplicationContextRunner runner() {
        List<String> words = List.of(value, "4.2", medicine, followUp);
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, new ExtractionPipelineAcceptanceTest.StubReply(reply(), words))
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withBean(RecordingDelivery.class, RecordingDelivery::new, bd -> bd.setPrimary(true))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.auth.hmac-secret=paper-delete-test-secret",
                        "baymax.auth.cookie-secure=false", "baymax.free.max-docs-per-month=50",
                        "baymax.web.public-origin=" + AppApiContext.ORIGIN,
                        // no scheduled writer runs behind the test's back; a held nudge is released only by evaluateAll()
                        "baymax.nudge.enabled=false", "baymax.urgency.sweep-cron=-",
                        // a released nudge is delivered (not parked for the reviewer), at any hour the test runs
                        "baymax.outbound.gate-nudges=false", "baymax.nudge.window-start-hour=0", "baymax.nudge.window-end-hour=24");
    }

    /** The delivery channel, recording every message it is handed; "delivered" as the log adapter delivers (sent_at set). */
    static final class RecordingDelivery implements MessageDeliveryPort {
        final List<OutboundMessage> handed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Mono<DeliveryOutcome> deliver(OutboundMessage message) {
            handed.add(message);
            return Mono.just(DeliveryOutcome.logged());
        }

        List<OutboundMessage> since(int mark, UUID familyId) {
            synchronized (handed) {
                return List.copyOf(handed.subList(mark, handed.size())).stream().filter(m -> familyId.equals(m.familyId())).toList();
            }
        }
    }

    // ---- the tests ----------------------------------------------------------------------------------------------

    @Test
    void deletingAPaperLeavesNoRowInAnyTableThatNamesIt() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Fixture f = paperOf(context, "+8801700000471");
            Ids ids = ids(f.document);

            // tables and texts the pipeline itself does not fill, seeded with one realistic row each:
            // - a pending explanation a reviewer could still approve (the pipeline's own was released and delivered);
            // - an unsent nudge made from each of the paper's rows, which name it only through trigger_key and copy its
            //   words into vars_json (nudge has no document_id: only the content scan sees these);
            // - a gated nudge's pending message, which carries no document_id either;
            // - the WhatsApp message the paper could have arrived in (V22), with Meta's media id, a handle that fetches
            //   the photo again: this paper came through the web, so the pipeline wrote none.
            UUID pending = seedPendingExplanation(f);
            try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.inbound_message "
                    + "(provider_key, family_id, patient_id, document_id, type, media_id) values (?, ?, ?, ?, 'image', ?)")) {
                s.setString(1, "e".repeat(32) + f.document.toString().replace("-", ""));
                s.setObject(2, f.patient.familyId());
                s.setObject(3, f.patient.id());
                s.setObject(4, f.document);
                s.setString(5, "MEDIA-" + value);
                s.executeUpdate();
            }
            seedNudge(f, "follow_up_due", "follow_up:" + ids.followUp, "held", "{\"instruction\":\"" + followUp + "\",\"due_date\":\"soon\"}", null);
            seedNudge(f, "course_ending", "medication:" + ids.medication, "held", "{\"medicine\":\"" + medicine + "\",\"duration_text\":\"7 days\"}", null);
            seedNudge(f, "trend", "trend:kalium:" + ids.observation, "held", "{\"marker\":\"" + value + "\",\"values\":\"4.2\"}", null);
            UUID gated = seedNudgeMessage(f, medicine + " changed");
            seedNudge(f, "medicine_changed", "document:" + f.document, "gated", "{\"changes\":\"" + medicine + "\"}", gated);
            // plan B3, now_ack: the owner's দেখেছি on the paper. now_ack names the paper by document_id with no cascade, so
            // the delete must remove it first; the sweep below finds the table from the schema and its guard fails
            // until a row is here
            exec("insert into baymax.now_ack (family_id, patient_id, document_id, urgency, reasons_digest, release_state) "
                    + "values (?, ?, ?, 'now', encode(sha256(convert_to('[]', 'UTF8')), 'hex'), 'none')",
                    f.patient.familyId(), f.patient.id(), f.document);

            List<String> needles = needles(f.document, ids);
            try (Connection c = connect()) {
                // every swept table holds this paper before the delete, so a table added later fails here until seeded
                assertThat(DeletionSweep.rowsByTable(c, f.document)).as("every document_id table holds the paper before the delete")
                        .allSatisfy((table, n) -> assertThat(n).as(table).isPositive());
                List<String> before = DeletionSweep.textHolding(c, needles);
                assertThat(DeletionSweep.absent(before, needles)).as("needles the paper never put in any text column").isEmpty();
                assertThat(before).as("the unsent nudge's words, before the delete").anyMatch(h -> h.startsWith("nudge.vars_json holds " + followUp));
            }
            assertThat(f.keysInBucket()).as("the page and the crops in the bucket before the delete").hasSizeGreaterThanOrEqualTo(2);

            String token = deleteToken(f.client, f.session, f.document);
            // the guard logs a 5xx's class at ERROR and its cause only at DEBUG: read here, so a red names the constraint
            ListAppender<ILoggingEvent> guard = new ListAppender<>();
            guard.start();
            Logger guardLog = (Logger) LoggerFactory.getLogger(AppApiGuard.class);
            ch.qos.logback.classic.Level guardLevel = guardLog.getLevel();
            guardLog.addAppender(guard);
            guardLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
            WebTestClient.ResponseSpec deleted;
            try {
                deleted = delete(f.client, f.session, f.document, token);
            } finally {
                guardLog.detachAppender(guard);
                guardLog.setLevel(guardLevel);
            }
            deleted.expectStatus().value(status -> assertThat(status).as("the delete; the guard's cause: %s", LogCapture.events(guard).stream()
                            .map(com.elioo.baymax.testsupport.LoggedText::of).toList()).isEqualTo(200))
                    .expectBody()
                    .jsonPath("$.objects_deleted").value(n -> assertThat(((Number) n).intValue()).isGreaterThanOrEqualTo(2))
                    .jsonPath("$.rows_deleted").value(n -> assertThat(((Number) n).intValue()).isPositive());

            try (Connection c = connect()) {
                assertThat(DeletionSweep.tablesStillHolding(c, f.document)).as("tables still holding the deleted document").isEmpty();
                assertThat(DeletionSweep.textStillHolding(c, needles)).as("text still holding the deleted paper").isEmpty();
                Map<String, Long> survivors = DeletionSweep.survivorsOf(c, f.document);
                assertThat(survivors.get("outbound_message")).as("the delivered explanation, kept pending P1").isPositive();
                assertThat(count(c, "select count(*) from baymax.audit_event where document_id = ? and kind = 'document_delete'", f.document))
                        .as("the delete's own audit row").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.outbound_message where id = ?", pending)).as("the pending explanation").isZero();
                assertThat(count(c, "select count(*) from baymax.outbound_message where id = ?", gated)).as("the gated nudge's message").isZero();
            }
            assertThat(f.keysInBucket()).as("the bucket under the paper's prefix after the delete").isEmpty();
        });
    }

    @Test
    void aHeldNudgeFromADeletedPaperIsNeverSent() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000472");
            Ids ids = ids(f.document);
            seedNudge(f, "course_ending", "medication:" + ids.medication, "held",
                    "{\"patient\":\"Ma\",\"medicine\":\"" + medicine + "\",\"duration_text\":\"7 days\",\"end_date\":\"tomorrow\"}", null);
            int mark = f.delivery.handed.size();

            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();
            context.getBean(NudgeUseCase.class).evaluateAll().block(Duration.ofSeconds(30));

            assertThat(f.delivery.since(mark, f.patient.familyId())).as("messages handed to the channel after the delete").isEmpty();
            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.outbound_message where kind = 'nudge' and family_id = ? "
                        + "and position('" + medicine + "' in coalesce(body, '')) > 0", f.patient.familyId()))
                        .as("a nudge composed from the deleted paper's medicine").isZero();
                assertThat(DeletionSweep.textStillHolding(c, List.of(medicine, ids.medication.toString())))
                        .as("text still holding the deleted paper's medicine").isEmpty();
            }
        });
    }

    /**
     * D1 (migration retake_replaces): deleting the blurred paper a retake replaced keeps the retake, unlinked. {@code ON DELETE SET NULL} is an
     * UPDATE OF {@code replaces_document_id} to NULL, which the retake_replaces migration's same-patient trigger must let through: without its NULL
     * pass this delete is a 500 carrying the trigger's message.
     */
    @Test
    void deletingAPaperThatARetakeReplacedKeepsTheRetakeUnlinked() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000487");
            UUID original = f.document;
            exec("update baymax.document set status = 'NEEDS_RETAKE', status_reason_code = 'low_overall' where id = ?", original);
            UUID retake = context.getBean(DocumentRecordPort.class)
                    .create(Document.received(f.patient.id(), f.patient.familyId(), 1, Instant.now())).block().id();
            context.getBean(DocumentRecordPort.class).linkRetake(retake, original).block();
            try (Connection c = connect()) {
                assertThat(one(c, "select replaces_document_id from baymax.document where id = ?", retake))
                        .as("the retake names the paper before the delete").isEqualTo(original);
            }

            delete(f.client, f.session, original, deleteToken(f.client, f.session, original)).expectStatus().isOk();

            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.document where id = ?", retake)).as("the retake survives").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.document where id = ? and replaces_document_id is null", retake))
                        .as("the retake, unlinked").isEqualTo(1);
                assertThat(DeletionSweep.tablesStillHolding(c, original)).as("tables still holding the deleted paper").isEmpty();
            }
        });
    }

    @Test
    void aPendingExplanationOfADeletedPaperCannotBeApproved() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000473");
            UUID pending = seedPendingExplanation(f);
            int mark = f.delivery.handed.size();

            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();

            StepVerifier.create(context.getBean(ReviewGateUseCase.class).approve(pending, "tanvir"))
                    .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(BaymaxException.class)
                            .extracting(x -> ((BaymaxException) x).status().value()).isEqualTo(404))
                    .verify(Duration.ofSeconds(30));
            assertThat(f.delivery.since(mark, f.patient.familyId())).as("messages handed to the channel after the delete").isEmpty();
        });
    }

    @Test
    void aViewOnlyMemberCannotDeleteAndNothingIsTouched() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000474");
            String memberNumber = "+8801700000475";
            AppApiContext.share(context, f.patient, memberNumber);
            FamilyAccount member = AppApiContext.family(context, memberNumber, "Sister");
            String memberSession = AppApiContext.session(context, member.id());
            String ownersToken = deleteToken(f.client, f.session, f.document);
            Snapshot before = f.snapshot();

            f.client.get().uri("/app/api/documents/" + f.document + "/delete-token").cookie("baymax_session", memberSession)
                    .exchange().expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
            delete(f.client, memberSession, f.document, ownersToken)
                    .expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
            // the member does see the paper, so the 403 is the ownership rule and not a 404 in disguise
            f.client.get().uri("/app/api/documents/" + f.document + "/status").cookie("baymax_session", memberSession)
                    .exchange().expectStatus().isOk();

            assertThat(f.snapshot()).as("rows, text and bucket after the member's attempts").isEqualTo(before);
        });
    }

    @Test
    void anotherFamilysDeleteIsNotFoundAndNothingIsTouched() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000476");
            FamilyAccount other = AppApiContext.family(context, "+8801700000477", "Karim");
            String otherSession = AppApiContext.session(context, other.id());
            String ownersToken = deleteToken(f.client, f.session, f.document);
            Snapshot before = f.snapshot();

            f.client.get().uri("/app/api/documents/" + f.document + "/delete-token").cookie("baymax_session", otherSession)
                    .exchange().expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
            delete(f.client, otherSession, f.document, ownersToken)
                    .expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);

            assertThat(f.snapshot()).as("rows, text and bucket after another family's attempts").isEqualTo(before);
        });
    }

    @Test
    void aDeleteWithoutAValidTokenIsRefusedAndNothingIsTouched() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000478");
            UUID another = AppApiContext.paper(context, POSTGRES, f.patient, "routine");
            String anotherPapersToken = deleteToken(f.client, f.session, another);
            String secondSession = AppApiContext.session(context, f.patient.familyId());
            String anotherSessionsToken = deleteToken(f.client, secondSession, f.document);
            Snapshot before = f.snapshot();

            f.client.method(HttpMethod.DELETE).uri("/app/api/documents/" + f.document).cookie("baymax_session", f.session)
                    .header(AppApiGuard.HEADER, "1").header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CONFIRM_REQUIRED\"}", true);
            for (String token : List.of(anotherPapersToken, anotherSessionsToken, "")) {
                delete(f.client, f.session, f.document, token)
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CONFIRM_REQUIRED\"}", true);
            }

            assertThat(f.snapshot()).as("rows, text and bucket after the refused deletes").isEqualTo(before);
            // the control: the owner's own token for this paper in this session deletes it
            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();
        });
    }

    /**
     * Review Focus 4: the family deletes a paper while the model is still reading it. Afterwards the pipeline must not
     * leave a crop, a row or an object behind for an id that no longer exists, nor compose or deliver an explanation of
     * it. The wait is on positive signals — the held reply went out, then the chain's own last line for this id — never
     * on the document being absent, which is true from the moment of the delete.
     */
    @Test
    void aPaperDeletedWhileItIsBeingReadLeavesNothingBehind() {
        PaintedPaper.Hold hold = PaintedPaper.Hold.ofTheModel();
        runner().withBean(PaintedPaper.Hold.class, () -> hold).run(context -> {
            assertThat(context).hasNotFailed();
            Fixture f = new Fixture(context, "+8801700000479");
            int mark = f.delivery.handed.size();
            try {
                f.document = upload(f);
                waitForStatus(f, "PROCESSING");
                delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();
            } finally {
                hold.modelReleased.tryEmitEmpty();
            }
            assertThat(hold.replied.await(30, TimeUnit.SECONDS)).as("the held model reply went out within 30 s").isTrue();
            String line = awaitChainEnd(f.document);
            // the path, not just the outcome: the chain's own stop ran, not a cleanup somewhere after "document finished"
            assertThat(chainLines()).as("the chain's stop for a paper deleted mid-read (chain ended: %s)", line)
                    .anyMatch(l -> l.contains("document deleted while being read documentId=" + f.document));

            try (Connection c = connect()) {
                assertThat(DeletionSweep.tablesStillHolding(c, f.document)).as("tables still holding the paper deleted mid-read (chain ended: %s)", line).isEmpty();
                assertThat(DeletionSweep.textStillHolding(c, List.of(value, medicine, followUp, f.document.toString())))
                        .as("text still holding the paper deleted mid-read").isEmpty();
                assertThat(count(c, "select count(*) from baymax.outbound_message where document_id = ?", f.document))
                        .as("messages about the paper deleted mid-read, delivered or not").isZero();
            }
            assertThat(f.keysInBucket()).as("the bucket under the paper deleted mid-read").isEmpty();
            assertThat(f.delivery.since(mark, f.patient.familyId())).as("messages handed to the channel").isEmpty();
        });
    }

    /**
     * The two cross-paper rules, in the shape production makes them (NudgeRules), not the one shape the first fixture
     * used. A trend is keyed to the run's FIRST reading ({@code trend:<marker>:<first observation id>}) and fires when
     * a new paper completes the run, so the paper a family deletes ("wrong paper") is usually the LATEST reading: its
     * trend is keyed to an older paper's observation, while {@code marker} and {@code values} quote this one. A
     * medicine_changed is keyed {@code document:<newer prescription>} and its {@code changes} quote the OLDER one's
     * medicines verbatim. Both are held, with every variable their copy needs, so a release would really send them.
     */
    @Test
    void crossPaperNudgesThatQuoteTheDeletedPaperAreWithdrawn() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000483");
            Ids ids = ids(f.document);
            // the marker the alias table gives this value (the needle is no real lab name, so it is named here)
            exec("update baymax.observation set canonical_name = 'potassium' where id = ?", ids.observation);
            // an earlier paper whose potassium reading opens the run: the trend is keyed to IT
            UUID older = AppApiContext.paper(context, POSTGRES, f.patient, "routine");
            UUID opening = insert("insert into baymax.observation (patient_id, document_id, name, canonical_name, value, unit, "
                    + "crop_key, observed_at) values (?, ?, 'Potassium', 'potassium', '3.9', 'mmol/L', ?, ?)",
                    f.patient.id(), older, StorageKeys.documentPrefix(f.patient.familyId(), f.patient.id(), older) + "crop-v1.jpg",
                    java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(60))));
            // a later prescription: its medicine_changed compares it with this paper, which is its "before"
            UUID newer = AppApiContext.paper(context, POSTGRES, f.patient, "routine");
            exec("update baymax.document set document_type = 'prescription', doc_date = date '2026-10-20' where id = ?", newer);
            UUID trend = seedNudge(f, "trend", "trend:potassium:" + opening, "held",
                    "{\"patient\":\"Ma\",\"marker\":\"" + value + "\",\"values\":\"3.9 mmol/L → 4.2 mmol/L\",\"count\":\"২\","
                            + "\"first_date\":\"১৩ আগস্ট\",\"last_date\":\"১২ অক্টোবর\",\"direction\":\"একটু একটু করে বাড়ছে\","
                            + "\"direction_en\":\"creeping up\"}", null);
            UUID changed = seedNudge(f, "medicine_changed", "document:" + newer, "held",
                    "{\"patient\":\"Ma\",\"changes\":\"" + medicine + " — 10 mg · 1+0+0 → (নতুন প্রেসক্রিপশনে নেই)\","
                            + "\"date\":\"২০ অক্টোবর\"}", null);
            try (Connection c = connect()) {
                assertThat(DeletionSweep.textHolding(c, List.of(value, medicine))).as("the cross-paper nudges quote the paper")
                        .contains("nudge.vars_json holds " + value + " x1", "nudge.vars_json holds " + medicine + " x1");
            }
            int mark = f.delivery.handed.size();

            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();
            context.getBean(NudgeUseCase.class).evaluateAll().block(Duration.ofSeconds(30));

            assertThat(f.delivery.since(mark, f.patient.familyId()).stream().map(OutboundMessage::body).toList())
                    .as("messages handed to the channel after the delete").isEmpty();
            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.nudge where id = ?", trend))
                        .as("the trend keyed to an older paper's reading, quoting this one").isZero();
                assertThat(count(c, "select count(*) from baymax.nudge where id = ?", changed))
                        .as("the medicine_changed keyed to a newer prescription, quoting this one").isZero();
                assertThat(DeletionSweep.textStillHolding(c, List.of(value, medicine))).as("text still holding the deleted paper").isEmpty();
            }
        });
    }

    /**
     * D2 (review finding 6): "a prescription dated on or after this paper" is the filed day, in Dhaka, the same order
     * medicationsOfPatient gives medicine_changed its sides in. The deleted prescription printed 1 November and was
     * uploaded at 10:00 UTC on 31 October; a later undated prescription was uploaded at 18:30 UTC that day, 00:30 on
     * 1 November in Dhaka, so both are filed under 1 November, the later upload is the newer paper, and its
     * medicine_changed quotes this one as its "before". On the UTC day of created_at
     * ({@code COALESCE(doc_date, created_at::date)}) the newer paper fell on 31 October, before this one, and its held
     * nudge survived the delete still quoting the deleted paper's medicines.
     */
    @Test
    void aMedicineChangedAcrossADhakaMidnightIsWithdrawnWithThePaperItQuotes() {
        // the session zone production sends (UTC): on a Dhaka laptop created_at::date would agree with the filed day by accident
        runner().withPropertyValues("spring.r2dbc.properties.timeZone=UTC").run(context -> {
            Fixture f = paperOf(context, "+8801700000486");
            exec("update baymax.document set document_type = 'prescription', doc_date = date '2026-11-01', "
                    + "created_at = timestamptz '2026-10-31T10:00:00Z' where id = ?", f.document);
            UUID newer = AppApiContext.paper(context, POSTGRES, f.patient, "routine");
            exec("update baymax.document set document_type = 'prescription', doc_date = null, "
                    + "created_at = timestamptz '2026-10-31T18:30:00Z' where id = ?", newer);
            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.document where id = ? and timeline_date = date '2026-11-01'", newer))
                        .as("the newer prescription is filed under the Dhaka day").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.medication_event where document_id = ?", f.document))
                        .as("the deleted paper has the medicines the nudge quotes").isPositive();
            }
            UUID changed = seedNudge(f, "medicine_changed", "document:" + newer, "held",
                    "{\"patient\":\"Ma\",\"changes\":\"" + medicine + " — 10 mg · 1+0+0 → (নতুন প্রেসক্রিপশনে নেই)\","
                            + "\"date\":\"১ নভেম্বর\"}", null);

            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();

            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.nudge where id = ?", changed))
                        .as("the medicine_changed of the newer prescription, quoting the deleted one").isZero();
            }
        });
    }

    /**
     * The other direction of the delete rule: what reached the family stays until PO decision P1, and "reached" is read
     * from the message, never from {@code nudge.status} alone. A gated nudge keeps {@code status = 'gated'} after the
     * reviewer approves it and WhatsApp accepts it (approve updates only the message), and that row is the cap ledger
     * ({@code countedSince} counts sent and gated): deleting it would let the family get one nudge more than the cap.
     */
    @Test
    void deliveredNudgesOfADeletedPaperAreKeptAndStillCounted() {
        runner().run(context -> {
            Fixture f = paperOf(context, "+8801700000484");
            Ids ids = ids(f.document);
            UUID sentMessage = seedDeliveredNudgeMessage(f, "released", "Ma's follow-up: " + followUp);
            UUID sent = seedNudge(f, "follow_up_due", "follow_up:" + ids.followUp, "sent",
                    "{\"patient\":\"Ma\",\"instruction\":\"" + followUp + "\"}", sentMessage);
            UUID approvedMessage = seedDeliveredNudgeMessage(f, "approved", "Ma's course of " + medicine + " ends");
            UUID approved = seedNudge(f, "course_ending", "medication:" + ids.medication, "gated",
                    "{\"patient\":\"Ma\",\"medicine\":\"" + medicine + "\"}", approvedMessage);
            NudgePort nudges = context.getBean(NudgePort.class);
            Instant week = Instant.now().minus(Duration.ofDays(7));
            long counted = nudges.countedSince(f.patient.id(), week).block();
            assertThat(counted).as("the cap ledger counts both delivered nudges before the delete").isEqualTo(2);

            delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();

            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.nudge where id = ?", sent))
                        .as("the sent nudge, delivered history kept pending P1").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.nudge where id = ?", approved))
                        .as("the gated nudge whose approved message WhatsApp accepted, kept pending P1").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.outbound_message where id = ?", sentMessage))
                        .as("the sent nudge's delivered message").isEqualTo(1);
                assertThat(count(c, "select count(*) from baymax.outbound_message where id = ?", approvedMessage))
                        .as("the approved nudge's delivered message").isEqualTo(1);
            }
            assertThat(nudges.countedSince(f.patient.id(), week).block()).as("the cap ledger after the delete").isEqualTo(counted);
        });
    }

    /**
     * Review Focus 4 on the path after extraction: the paper is DONE and its explanation is being written (the EXPLAIN
     * reply is held) when the family deletes it. Whatever the writer — this chain, the urgency sweep, a retake — a
     * message naming a paper that no longer exists is never stored, so it is neither delivered nor left for the
     * reviewer to approve. The wait is on positive signals: the held call arrived, the reply went, the chain's last line.
     */
    @Test
    void anExplanationFinishedAfterTheDeleteIsNeitherStoredNorSent() {
        PaintedPaper.Hold hold = PaintedPaper.Hold.ofTheExplanation();
        runner().withBean(PaintedPaper.Hold.class, () -> hold).run(context -> {
            assertThat(context).hasNotFailed();
            Fixture f = new Fixture(context, "+8801700000485");
            int mark = f.delivery.handed.size();
            try {
                f.document = upload(f);
                assertThat(hold.asked.await(30, TimeUnit.SECONDS)).as("the explanation's model call arrived and is held").isTrue();
                delete(f.client, f.session, f.document, deleteToken(f.client, f.session, f.document)).expectStatus().isOk();
            } finally {
                hold.modelReleased.tryEmitEmpty();
            }
            assertThat(hold.replied.await(30, TimeUnit.SECONDS)).as("the held EXPLAIN reply went out within 30 s").isTrue();
            String line = awaitChainEnd(f.document);

            assertThat(f.delivery.since(mark, f.patient.familyId()).stream().map(OutboundMessage::body).toList())
                    .as("messages handed to the channel (chain ended: %s)", line).isEmpty();
            try (Connection c = connect()) {
                assertThat(count(c, "select count(*) from baymax.outbound_message where document_id = ?", f.document))
                        .as("messages naming the paper deleted while its explanation was written, delivered or not").isZero();
                assertThat(DeletionSweep.tablesStillHolding(c, f.document)).as("tables still holding the paper").isEmpty();
                assertThat(DeletionSweep.textStillHolding(c, List.of(value, medicine, followUp, f.document.toString())))
                        .as("text still holding the paper").isEmpty();
            }
            assertThat(f.keysInBucket()).as("the bucket under the paper").isEmpty();
        });
    }

    // ---- fixture ------------------------------------------------------------------------------------------------

    /** One family, its patient, an owner's session and a client; {@link #document} once a paper is uploaded. */
    final class Fixture {
        final ApplicationContext context;
        final PatientProfile patient;
        final String session;
        final WebTestClient client;
        final RecordingDelivery delivery;
        UUID document;

        Fixture(ApplicationContext context, String number) {
            this.context = context;
            FamilyAccount family = AppApiContext.family(context, number, "Rahim");
            this.patient = AppApiContext.patient(context, family.id(), "Ma");
            this.session = AppApiContext.session(context, family.id());
            this.client = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();
            this.delivery = context.getBean(RecordingDelivery.class);
        }

        List<String> keysInBucket() {
            return context.getBean(StoragePort.class)
                    .listKeys(StorageKeys.documentPrefix(patient.familyId(), patient.id(), document)).collectList().block();
        }

        Snapshot snapshot() throws Exception {
            try (Connection c = connect()) {
                List<String> needles = List.of(value, medicine, followUp, document.toString());
                return new Snapshot(DeletionSweep.rowsByTable(c, document), DeletionSweep.textHolding(c, needles), keysInBucket());
            }
        }
    }

    record Snapshot(Map<String, Long> rows, List<String> text, List<String> bucket) {
    }

    /** The rows the pipeline made for the paper: one of each kind, read before the delete. */
    record Ids(UUID observation, UUID medication, UUID followUp) {
    }

    /** The paper's words, its id and its rows' ids: what the content scan looks for. */
    private List<String> needles(UUID document, Ids ids) {
        return List.of(value, medicine, followUp, document.toString(), ids.observation().toString(), ids.medication().toString(),
                ids.followUp().toString());
    }

    /** A family with a paper uploaded through {@code POST /app/add} and read to DONE, its chain run to the end. */
    private Fixture paperOf(ApplicationContext context, String number) throws Exception {
        Fixture f = new Fixture(context, number);
        f.document = upload(f);
        waitForStatus(f, "DONE");
        assertThat(awaitChainEnd(f.document)).as("the chain's last line").contains("document finished");
        try (Connection c = connect()) {
            assertThat(count(c, "select count(*) from baymax.outbound_message where document_id = ? and kind = 'explanation' "
                    + "and sent_at is not null", f.document)).as("the paper's own explanation, delivered").isEqualTo(1);
        }
        return f;
    }

    private static UUID upload(Fixture f) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", f.patient.id().toString());
        body.part("upload_key", UUID.randomUUID().toString());
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        String location = f.client.post().uri("/app/add").cookie("baymax_session", f.session)
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).as("the upload's redirect").startsWith("/app/documents/");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    /** Polls the JSON status (30 s at most) until it reads {@code status}. */
    private static void waitForStatus(Fixture f, String status) throws InterruptedException {
        String seen = null;
        for (int i = 0; i < 60; i++) {
            seen = f.client.get().uri("/app/api/documents/" + f.document + "/status").cookie("baymax_session", f.session).exchange()
                    .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("status").toString();
            if (status.equals(seen)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the paper never reached " + status + " in 30 s; last seen " + seen);
    }

    /**
     * The intake chain's terminal line for this id, on whichever branch it ended: finished (DocumentIntakeService), ended
     * in error (DocumentIntakeService), or stopped for a paper deleted while it was being read (DocumentProcessingChain).
     */
    private String awaitChainEnd(UUID document) throws InterruptedException {
        List<String> ends = List.of("document finished documentId=" + document, "document processing ended in error documentId=" + document,
                "document deleted while being read documentId=" + document);
        for (int i = 0; i < 120; i++) {
            for (String l : chainLines()) {
                if (ends.stream().anyMatch(l::contains)) {
                    return l;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out after 30 s waiting for the intake chain's last line for " + document);
    }

    private List<String> chainLines() {
        // not synchronized (chain.list): doAppend holds the appender's monitor, not the list's, so that lock guarded nothing
        return LogCapture.events(chain).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String deleteToken(WebTestClient client, String session, UUID document) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = client.get().uri("/app/api/documents/" + document + "/delete-token").cookie("baymax_session", session).exchange()
                .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).containsOnlyKeys("confirm_token", "expires_at");
        Instant expires = Instant.parse(body.get("expires_at").toString());
        assertThat(expires).isBetween(Instant.now().plus(Duration.ofMinutes(9)), Instant.now().plus(Duration.ofMinutes(11)));
        return body.get("confirm_token").toString();
    }

    private static WebTestClient.ResponseSpec delete(WebTestClient client, String session, UUID document, String token) {
        return client.method(HttpMethod.DELETE).uri("/app/api/documents/" + document).cookie("baymax_session", session)
                .header(AppApiGuard.HEADER, "1").header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("confirm_token", token)).exchange();
    }

    private static Ids ids(UUID document) throws Exception {
        try (Connection c = connect()) {
            return new Ids(one(c, "select id from baymax.observation where document_id = ?", document),
                    one(c, "select id from baymax.medication_event where document_id = ?", document),
                    one(c, "select id from baymax.follow_up where document_id = ?", document));
        }
    }

    private UUID seedPendingExplanation(Fixture f) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.outbound_message "
                + "(family_id, patient_id, document_id, kind, urgency, body, gate_status) values (?, ?, ?, 'explanation', 'routine', ?, 'pending') "
                + "returning id")) {
            s.setObject(1, f.patient.familyId());
            s.setObject(2, f.patient.id());
            s.setObject(3, f.document);
            s.setString(4, "Ma's paper lists " + medicine + "; a pending explanation awaiting the reviewer");
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** A gated nudge's message waiting for the reviewer: a nudge message names no document, only its words do. */
    private static UUID seedNudgeMessage(Fixture f, String body) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.outbound_message "
                + "(family_id, patient_id, kind, urgency, body, gate_status) values (?, ?, 'nudge', 'routine', ?, 'pending') returning id")) {
            s.setObject(1, f.patient.familyId());
            s.setObject(2, f.patient.id());
            s.setString(3, body);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /**
     * A nudge message the provider accepted (V14: {@code sent_at} with {@code delivery_status = 'sent'} and the
     * provider's id; a fictional one), released at once or approved by the reviewer.
     */
    private static UUID seedDeliveredNudgeMessage(Fixture f, String gateStatus, String body) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.outbound_message "
                + "(family_id, patient_id, kind, urgency, body, gate_status, reviewer, decided_at, sent_at, delivery_status, "
                + "provider_message_id) values (?, ?, 'nudge', 'routine', ?, ?, ?, ?, now(), 'sent', ?) returning id")) {
            s.setObject(1, f.patient.familyId());
            s.setObject(2, f.patient.id());
            s.setString(3, body);
            s.setString(4, gateStatus);
            boolean approved = "approved".equals(gateStatus);
            s.setString(5, approved ? "tanvir" : null);
            s.setObject(6, approved ? java.sql.Timestamp.from(Instant.now()) : null);
            s.setString(7, "wamid.TEST-" + UUID.randomUUID());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** Runs one statement over the test's own connection, binding {@code args} in order. */
    private static UUID insert(String sql, Object... args) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql + " returning id")) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void exec(String sql, Object... args) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            s.executeUpdate();
        }
    }

    private static UUID seedNudge(Fixture f, String rule, String trigger, String status, String vars, UUID message) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.nudge (family_id, patient_id, rule, "
                + "trigger_key, urgency, status, vars_json, message_id, hold_until) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?) "
                + "returning id")) {
            s.setObject(1, f.patient.familyId());
            s.setObject(2, f.patient.id());
            s.setString(3, rule);
            s.setString(4, trigger);
            s.setString(5, "trend".equals(rule) ? "this_week" : "routine");
            s.setString(6, status);
            s.setString(7, vars);
            s.setObject(8, message);
            s.setObject(9, "held".equals(status) ? java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))) : null);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID one(Connection c, String sql, UUID document) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, document);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("a row for " + sql).isTrue();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static long count(Connection c, String sql, UUID id) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
