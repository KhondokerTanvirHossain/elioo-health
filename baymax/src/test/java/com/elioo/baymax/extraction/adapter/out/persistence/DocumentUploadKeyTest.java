package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.application.service.DocumentIntakeService;
import com.elioo.baymax.extraction.application.service.DocumentProcessingChain;
import com.elioo.baymax.extraction.application.service.PageRenderer;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3's retry rule on real Postgres (V21). A mock ledger cannot fail the way the database does (CLAUDE.md, DR-22): a
 * second insert with the same key comes back as "already there", the database refuses a duplicate from any writer, and
 * a press that waits on another's uncommitted row gets that row back rather than a second one.
 */
@Testcontainers
class DocumentUploadKeyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static PatientProfile patient(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "A", FamilyAccount.Plan.FREE, T, T)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
    }

    @Test
    void theSameKeyTwiceIsOneDocumentAndAnotherFamilysSameKeyIsItsOwn() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            PatientProfile pa = patient(context, "+8801700000721");
            PatientProfile pb = patient(context, "+8801700000722");

            CreatedDocument first = documents.createOnce(Document.received(pa.id(), pa.familyId(), 2, T), "k-0f8e2c1a").block();
            CreatedDocument retry = documents.createOnce(Document.received(pa.id(), pa.familyId(), 2, T), "k-0f8e2c1a").block();
            CreatedDocument other = documents.createOnce(Document.received(pb.id(), pb.familyId(), 1, T), "k-0f8e2c1a").block();

            assertThat(first.fresh()).isTrue();
            assertThat(first.document().pageCount()).isEqualTo(2);
            assertThat(first.document().status()).isEqualTo(Document.Status.RECEIVED);
            assertThat(retry.fresh()).isFalse();
            assertThat(retry.document().id()).isEqualTo(first.document().id());
            assertThat(other.fresh()).isTrue();
            assertThat(other.document().id()).isNotEqualTo(first.document().id());
            assertThat(documents.findByUploadKey(pa.familyId(), "k-0f8e2c1a").block().id()).isEqualTo(first.document().id());
            assertThat(documents.findByUploadKey(pb.familyId(), "k-0f8e2c1a").block().id()).isEqualTo(other.document().id());
            // asserted on the table, not on the port's own answer: one row per family, two families
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + pa.familyId()
                    + "' AND upload_key = 'k-0f8e2c1a'")).isEqualTo("1");
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE upload_key = 'k-0f8e2c1a'")).isEqualTo("2");

            // the pipeline's own writes (writeDocument) never name the key, so a document that moved on is still found
            documents.update(first.document().withStatus(Document.Status.PROCESSING, null, T.plusSeconds(5))).block();
            assertThat(documents.findByUploadKey(pa.familyId(), "k-0f8e2c1a").block().status()).isEqualTo(Document.Status.PROCESSING);
        });
    }

    /** No key (WhatsApp, the JSON API) is never a replay: each call is a new row, and nothing is looked up. */
    @Test
    void withoutAKeyEveryCallIsANewDocument() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            PatientProfile p = patient(context, "+8801700000725");
            CreatedDocument one = documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), null).block();
            CreatedDocument two = documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), null).block();
            assertThat(one.fresh()).isTrue();
            assertThat(two.fresh()).isTrue();
            assertThat(two.document().id()).isNotEqualTo(one.document().id());
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + p.familyId()
                    + "' AND upload_key IS NULL")).isEqualTo("2");
        });
    }

    @Test
    void theDatabaseRefusesASecondRowWithTheKeyFromAnyWriterButNotTwoRowsWithoutOne() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000723");
            DatabaseClient db = context.getBean(DatabaseClient.class);
            String insert = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id, upload_key) VALUES (:p, :f, :k)";
            db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block();
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block())
                    .hasMessageContaining("document_upload_key_uq");
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "bad key!").then().block())
                    .hasMessageContaining("document_upload_key_check");
            db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0002").then().block();   // a well-formed new key
            String plain = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id) VALUES (:p, :f)";
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();   // WhatsApp/API rows: no key, no conflict
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + p.familyId() + "'"))
                    .isEqualTo("4");
        });
    }

    /**
     * The race, made deterministic: the winner's row is inserted on its own connection and held uncommitted, the loser's
     * {@code createOnce} is started and seen waiting on that row's lock, then the winner commits. The loser must come
     * back with the winner's row and {@code fresh = false}, and one row exists. A read-then-insert {@code createOnce}
     * fails here every time (its read cannot see the uncommitted row, so its insert hits {@code document_upload_key_uq});
     * two presses fired together only overlap when the scheduler happens to let them.
     */
    @Test
    void theLoserWaitsOnTheWinnersUncommittedRowAndGetsItBack() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000729");
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            String url = POSTGRES.getJdbcUrl() + "&sslmode=disable";
            try (Connection winner = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 Connection watch = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
                winner.setAutoCommit(false);
                UUID winnerId;
                try (PreparedStatement insert = winner.prepareStatement("INSERT INTO " + BaymaxSchema.NAME
                        + ".document (patient_id, family_id, upload_key) VALUES (?, ?, 'k-held-0001') RETURNING id")) {
                    insert.setObject(1, p.id());
                    insert.setObject(2, p.familyId());
                    try (ResultSet rs = insert.executeQuery()) {
                        rs.next();
                        winnerId = rs.getObject(1, UUID.class);
                    }
                }
                CompletableFuture<CreatedDocument> loser =
                        documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), "k-held-0001").toFuture();
                long deadline = System.currentTimeMillis() + 10_000;
                while (!loser.isDone() && !waitingOnALock(watch)) {
                    assertThat(System.currentTimeMillis()).as("the loser waits on the winner's row").isLessThan(deadline);
                    Thread.sleep(20);
                }
                winner.commit();
                CreatedDocument got = loser.get(10, TimeUnit.SECONDS);
                assertThat(got.fresh()).as("the loser made nothing").isFalse();
                assertThat(got.document().id()).as("the loser gets the winner's row").isEqualTo(winnerId);
                assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE upload_key = 'k-held-0001'")).isEqualTo("1");
            }
        });
    }

    /**
     * The restored sheet, end to end on Postgres through the real intake: Back brings the sheet back with its key. The
     * same paper sent again is the same document; another paper chosen on it is a new one, never the old one handed
     * back. Rows are counted on the table.
     */
    @Test
    void onTheRealDatabaseTheSamePaperUnderAKeyIsOneDocumentAndAnotherPaperIsItsOwn() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000726");
            DocumentIntakeService intake = intakeOn(context);
            Upload red = page(0xFF0000);
            Upload blue = page(0x0000FF);

            CreatedDocument first = intake.accept(p.id(), List.of(red), "k-restored-01").block();
            CreatedDocument resent = intake.accept(p.id(), List.of(red), "k-restored-01").block();
            CreatedDocument another = intake.accept(p.id(), List.of(blue), "k-restored-01").block();

            assertThat(first.fresh()).isTrue();
            assertThat(resent.fresh()).as("the same paper again made nothing").isFalse();
            assertThat(resent.document().id()).isEqualTo(first.document().id());
            assertThat(another.fresh()).as("another paper is read").isTrue();
            assertThat(another.document().id()).as("another paper is its own document").isNotEqualTo(first.document().id());
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE patient_id = '" + p.id() + "'")).isEqualTo("2");
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE patient_id = '" + p.id()
                    + "' AND upload_key LIKE 'k-restored-01:%'")).as("both rows carry the press's key").isEqualTo("2");
        });
    }

    /** The real intake on this database; storage, the allowance and the models answer nothing, as intake tests have them. */
    private static DocumentIntakeService intakeOn(ApplicationContext context) {
        DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
        FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
        when(freeTier.checkCanUploadDocument(any(UUID.class))).thenReturn(Mono.empty());
        when(freeTier.checkCanUploadDocuments(any(UUID.class), anyInt())).thenReturn(Mono.empty());
        DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenReturn(Mono.empty());
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        when(extraction.process(any(), any())).thenReturn(Mono.empty());
        ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
        when(explainer.explain(any())).thenReturn(Mono.empty());
        NudgeUseCase nudges = mock(NudgeUseCase.class);
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        DocumentUrgencyUseCase urgency = mock(DocumentUrgencyUseCase.class);
        when(urgency.assessAndStore(any())).thenReturn(Mono.empty());
        Clock clock = Clock.fixed(T, ZoneOffset.UTC);
        return new DocumentIntakeService(context.getBean(HealthRecordPort.class), documents, freeTier, storage, new PageRenderer(),
                new BaymaxProperties(), clock, new DocumentProcessingChain(extraction, explainer, nudges, documents, clock, urgency, storage));
    }

    /** A photo of a page painted one colour: another colour is another paper. */
    private static Upload page(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new Upload("page.jpg", out.toByteArray());
    }

    /** True while some statement on the document table waits on a lock: the loser's insert, behind the winner's row. */
    private static boolean waitingOnALock(Connection watch) throws Exception {
        try (Statement st = watch.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity "
                + "WHERE wait_event_type = 'Lock' AND query ILIKE 'INSERT INTO%document%'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
