package com.elioo.baymax.extraction;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.RecoveryReport;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F9b against real Postgres and MinIO: the path with the {@code stored_object.storage_key} UNIQUE constraint. A
 * document killed mid-crop has its first crop in the bucket and the ledger already; crop keys are deterministic, so
 * a naive re-run collides with its own leftover and ends FAILED. Vision and the model are the stubs of
 * {@link ExtractionPipelineAcceptanceTest}.
 */
@Testcontainers
class DocumentRecoveryAcceptanceTest {

    static final String BUCKET = "baymax";
    /** Fixture families stay inside the fictional block +8801700000000–+8801700000999. */
    private static final AtomicInteger NEXT_NUMBER = new AtomicInteger(100);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    @BeforeAll
    static void createBucket() {
        try (S3Client admin = adminClient()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private static S3Client adminClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1).forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build();
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withUserConfiguration(ExtractionPipelineAcceptanceTest.StubbedProviders.class)
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.extract.vision-model=",           // text-only: no vision client in this run
                        "baymax.llm.prices.groq.[openai/gpt-oss-120b].input=0.15",
                        "baymax.llm.prices.groq.[openai/gpt-oss-120b].output=0.60",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword(),
                        "baymax.storage.endpoint=" + MINIO.getS3URL(),
                        "baymax.storage.region=us-east-1",
                        "baymax.storage.bucket=" + BUCKET,
                        "baymax.storage.access-key=" + MINIO.getUserName(),
                        "baymax.storage.secret-key=" + MINIO.getPassword(),
                        "baymax.storage.path-style=true",
                        "baymax.storage.credentials=static",
                        "baymax.storage.server-side-encryption=");
    }

    @Test
    void aDocumentKilledMidCropIsReprocessedToDoneNotFailedOnItsOwnLeftoverCrop() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            DocumentStorageUseCase storage = context.getBean(DocumentStorageUseCase.class);
            Instant beforeBoot = Instant.now().minusSeconds(600);

            // what `docker rm -f` (deploy.sh) leaves: PROCESSING, page stored, first crop already in bucket + ledger
            Document doc = records.create(Document.received(patient, family, 1, beforeBoot)).block();
            records.update(doc.withStatus(Document.Status.PROCESSING, null, beforeBoot)).block();
            storage.storePage(family, patient, doc.id(), 1, pageJpeg()).block();
            storage.storeCrop(family, patient, doc.id(), "v1", pageJpeg()).block();

            var logged = captureExtractionFailures();
            List<RecoveryReport> reports;
            try {
                reports = context.getBean(DocumentRecoveryUseCase.class)
                        .recoverInterrupted(Instant.now()).collectList().block(Duration.ofSeconds(60));
            } finally {
                release(logged);
            }

            // status_reason is cut at 180 characters, before Postgres names the constraint; the DEBUG detail line
            // carries the whole exception chain, so a re-run that collides with its own leftover crop says so here
            assertThat(failureDetails(logged, doc.id()))
                    .as("extraction failures while recovering (exception chain)").isEmpty();
            assertThat(scalar("select coalesce(status_reason, '') from baymax.document where id = '" + doc.id() + "'"))
                    .as("why the recovered document did not finish").isEmpty();
            assertThat(scalar("select status from baymax.document where id = '" + doc.id() + "'")).isEqualTo("DONE");
            assertThat(scalar("select count(*) from baymax.stored_object where document_id = '" + doc.id() + "' and kind = 'crop'"))
                    .as("one crop per value, the leftover replaced, not duplicated").isEqualTo("1");
            assertThat(scalar("select count(*) from baymax.observation where document_id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(scalar("select recovery_attempts from baymax.document where id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(outcomesFor(reports, doc.id())).containsExactly("reprocessed");
        });
    }

    @Test
    void aDocumentReceivedAfterTheCutoffIsLeftForTheSubscriptionAlreadyRunningIt() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            Instant cutoff = Instant.now().minusSeconds(60);
            Document fresh = context.getBean(DocumentRecordPort.class)
                    .create(Document.received(patient, family, 1, Instant.now())).block();

            context.getBean(DocumentRecoveryUseCase.class).recoverInterrupted(cutoff).collectList().block();

            assertThat(scalar("select status || '/' || recovery_attempts from baymax.document where id = '" + fresh.id() + "'"))
                    .isEqualTo("RECEIVED/0");
        });
    }

    /**
     * The guard in {@code markRecoveryAttempt}'s own SQL. Everywhere else the service's Java filter on
     * {@code updatedAt} runs first and hides it, so it is called directly: a document updated at the cutoff (this
     * instance's own work) or no longer RECEIVED/PROCESSING is not counted, and an interrupted one is counted once per
     * sweep.
     */
    @Test
    void markRecoveryAttemptCountsOnlyAnInterruptedDocumentUpdatedBeforeTheCutoff() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            Instant cutoff = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);

            Document atCutoff = processing(records, patient, family, cutoff);
            Document finished = done(records, patient, family, cutoff.minusSeconds(60));
            Document interrupted = processing(records, patient, family, cutoff.minusSeconds(60));

            assertThat(records.markRecoveryAttempt(atCutoff.id(), cutoff).blockOptional())
                    .as("PROCESSING but updated at the cutoff: this instance's own work").isEmpty();
            assertThat(records.markRecoveryAttempt(finished.id(), cutoff).blockOptional())
                    .as("DONE before the cutoff: nothing was interrupted").isEmpty();
            assertThat(scalar("select string_agg(recovery_attempts::text, ',' order by updated_at) from baymax.document"
                    + " where id in ('" + atCutoff.id() + "','" + finished.id() + "')"))
                    .as("neither was counted").isEqualTo("0,0");
            assertThat(records.markRecoveryAttempt(interrupted.id(), cutoff).blockOptional()).contains(1);
            assertThat(records.markRecoveryAttempt(interrupted.id(), cutoff.plusSeconds(1)).blockOptional())
                    .as("the next restart's sweep").contains(2);
        });
    }

    /** chain.fail on a real database: the reason survives, and the attempt that found the pages missing is counted. */
    @Test
    void aDocumentWhosePagesWereNeverStoredIsFailedWithItsReason() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            Document lost = context.getBean(DocumentRecordPort.class)
                    .create(Document.received(patient, family, 1, Instant.now().minusSeconds(600))).block();

            List<RecoveryReport> reports = context.getBean(DocumentRecoveryUseCase.class)
                    .recoverInterrupted(Instant.now()).collectList().block(Duration.ofSeconds(60));

            assertThat(outcomesFor(reports, lost.id())).containsExactly("failed_pages_missing");
            assertThat(scalar("select status || '/' || coalesce(status_reason, '') || '/' || recovery_attempts"
                    + " from baymax.document where id = '" + lost.id() + "'"))
                    .isEqualTo("FAILED/pages_missing_after_restart/1");
        });
    }

    /**
     * The crash-loop bound on a real database: the attempt is committed BEFORE the work, so a re-run that errors (the
     * page's ledger row exists, its object is gone from the bucket) still leaves it counted. Were the increment rolled
     * back with the work, a document that kills the JVM would be re-run on every restart for ever.
     */
    @Test
    void anAttemptThatErrorsStaysCounted() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            Document doc = processing(records, patient, family, Instant.now().minusSeconds(600));
            context.getBean(DocumentStorageUseCase.class).storePage(family, patient, doc.id(), 1, pageJpeg()).block();
            try (S3Client admin = adminClient()) {
                admin.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET)
                        .key(StorageKeys.page(family, patient, doc.id(), 1)).build());
            }

            List<RecoveryReport> reports = context.getBean(DocumentRecoveryUseCase.class)
                    .recoverInterrupted(Instant.now()).collectList().block(Duration.ofSeconds(60));

            assertThat(outcomesFor(reports, doc.id())).containsExactly("error");
            assertThat(scalar("select status || '/' || recovery_attempts from baymax.document where id = '" + doc.id() + "'"))
                    .as("left as it was, the attempt counted").isEqualTo("PROCESSING/1");
        });
    }

    /**
     * The SQL behind "finished but never explained": a DONE document with an explanation row is not picked, nor a
     * NEEDS_RETAKE one with a retake row (or every restart within the window would ask the family again and escalate
     * to retake.second); one with no message, or with only a detail or nudge row, is picked; one older than the
     * window is only counted. Pinned a day in the past so the other tests' documents, all updated "now", cannot fall
     * inside either window.
     */
    @Test
    void unexplainedSinceFindsADoneDocumentWithNoMessageAndNotOneWithAnExplanation() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            Instant before = Instant.now().minus(Duration.ofDays(1));
            Instant since = before.minus(Duration.ofHours(6));

            Document explained = done(records, patient, family, before.minus(Duration.ofHours(1)));
            Document silent = done(records, patient, family, before.minus(Duration.ofHours(2)));
            Document retaken = finished(records, patient, family, Document.Status.NEEDS_RETAKE,
                    before.minus(Duration.ofMinutes(170)));
            Document detailOnly = done(records, patient, family, before.minus(Duration.ofMinutes(160)));
            Document nudgeOnly = done(records, patient, family, before.minus(Duration.ofMinutes(150)));
            Document old = done(records, patient, family, since.minus(Duration.ofHours(1)));
            message(family, patient, explained, "explanation");
            message(family, patient, retaken, "retake");
            message(family, patient, detailOnly, "detail");
            message(family, patient, nudgeOnly, "nudge");

            List<UUID> listed = records.unexplainedSince(since, before).collectList().block();
            assertThat(listed).as("a retake message answers a NEEDS_RETAKE document").doesNotContain(retaken.id());
            assertThat(listed).as("a detail or nudge row is not an explanation").contains(detailOnly.id(), nudgeOnly.id());
            assertThat(listed).as("inside the window and never explained, oldest first")
                    .containsExactly(detailOnly.id(), nudgeOnly.id(), silent.id());
            assertThat(records.countUnexplainedBefore(since).block())
                    .as("older than the window, never explained: counted, not listed").isEqualTo(1L);
            assertThat(old.id()).isNotIn(records.unexplainedSince(since, before).collectList().block());
        });
    }

    private static Document done(DocumentRecordPort records, UUID patient, UUID family, Instant at) {
        return finished(records, patient, family, Document.Status.DONE, at);
    }

    private static Document processing(DocumentRecordPort records, UUID patient, UUID family, Instant at) {
        Document created = records.create(Document.received(patient, family, 1, at)).block();
        return records.update(created.withStatus(Document.Status.PROCESSING, null, at)).block();
    }

    private static Document finished(DocumentRecordPort records, UUID patient, UUID family, Document.Status status,
                                     Instant at) {
        Document created = records.create(Document.received(patient, family, 1, at)).block();
        return records.update(created.withStatus(status, null, at)).block();
    }

    private static void message(UUID family, UUID patient, Document document, String kind) throws Exception {
        sql("insert into baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, gate_status) values ('"
                + family + "','" + patient + "','" + document.id() + "','" + kind + "','routine','released')");
    }

    /** Outcomes the sweep reported for one document; the sweep also reports the other tests' leftovers. */
    private static List<String> outcomesFor(List<RecoveryReport> reports, UUID documentId) {
        return reports.stream().filter(r -> r.documentId().equals(documentId)).map(RecoveryReport::outcome).toList();
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureExtractionFailures() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(com.elioo.baymax.extraction.application.service.DocumentExtractionService.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);   // the exception chain is logged at DEBUG only (PR 1)
        return logged;
    }

    private static void release(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(com.elioo.baymax.extraction.application.service.DocumentExtractionService.class);
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    /**
     * The "extraction failure detail" events for one document, as the messages of its exception chain. Per document:
     * the class shares one database, and every sweep also re-runs what the other tests left RECEIVED/PROCESSING.
     */
    private static List<String> failureDetails(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged, UUID documentId) {
        return logged.list.stream()
                .filter(e -> e.getFormattedMessage().contains("extraction failure detail")
                        && e.getFormattedMessage().contains(documentId.toString()))
                .map(e -> {
                    StringBuilder chain = new StringBuilder();
                    for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                        chain.append(t.getClassName()).append(": ").append(t.getMessage()).append(" | ");
                    }
                    return chain.toString();
                })
                .toList();
    }

    private static byte[] pageJpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private static void seedFamily(UUID family, UUID patient) throws Exception {
        sql("insert into baymax.family_account (id, whatsapp_number, owner_name, terms_accepted_at) values ('"
                + family + "','+8801700000" + NEXT_NUMBER.getAndIncrement() + "','o', now())");
        sql("insert into baymax.patient_profile (id, family_id, name, age, sex, proxy_consent_at) values ('"
                + patient + "','" + family + "','p',70,'female', now())");
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable",
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
