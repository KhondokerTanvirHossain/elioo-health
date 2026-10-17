package com.elioo.baymax.extraction;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.RecoveryReport;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
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
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1).forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
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
            assertThat(failureDetails(logged))
                    .as("extraction failures while recovering (exception chain)").isEmpty();
            assertThat(scalar("select coalesce(status_reason, '') from baymax.document where id = '" + doc.id() + "'"))
                    .as("why the recovered document did not finish").isEmpty();
            assertThat(scalar("select status from baymax.document where id = '" + doc.id() + "'")).isEqualTo("DONE");
            assertThat(scalar("select count(*) from baymax.stored_object where document_id = '" + doc.id() + "' and kind = 'crop'"))
                    .as("one crop per value, the leftover replaced, not duplicated").isEqualTo("1");
            assertThat(scalar("select count(*) from baymax.observation where document_id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(scalar("select recovery_attempts from baymax.document where id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(reports).extracting(RecoveryReport::outcome).containsExactly("reprocessed");
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
     * The SQL behind "finished but never explained": a DONE document with an explanation row is not picked, one
     * without is, and one older than the window is only counted. Pinned a day in the past so the other tests'
     * documents, all updated "now", cannot fall inside either window.
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
            Document old = done(records, patient, family, since.minus(Duration.ofHours(1)));
            sql("insert into baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, gate_status) values ('"
                    + family + "','" + patient + "','" + explained.id() + "','explanation','routine','released')");

            assertThat(records.unexplainedSince(since, before).collectList().block())
                    .as("inside the window and never explained").containsExactly(silent.id());
            assertThat(records.countUnexplainedBefore(since).block())
                    .as("older than the window, never explained: counted, not listed").isEqualTo(1L);
            assertThat(old.id()).isNotIn(records.unexplainedSince(since, before).collectList().block());
        });
    }

    private static Document done(DocumentRecordPort records, UUID patient, UUID family, Instant at) {
        Document created = records.create(Document.received(patient, family, 1, at)).block();
        return records.update(created.withStatus(Document.Status.DONE, null, at)).block();
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

    /** Every "extraction failure detail" event, as the messages of its exception chain. */
    private static List<String> failureDetails(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        return logged.list.stream()
                .filter(e -> e.getFormattedMessage().contains("extraction failure detail"))
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
