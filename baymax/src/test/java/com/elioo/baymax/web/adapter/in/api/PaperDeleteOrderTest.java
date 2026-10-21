package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.adapter.out.s3.S3StorageAdapter;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.testsupport.DeletionSweep;
import com.elioo.baymax.testsupport.PaintedPaper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order of a paper's delete, both directions (plan B4c, written before the reorder): rows first and committed, the
 * bucket after, as {@code FamilyAccountService.deleteFamily} does. When the rows cannot go, the images must stay — the
 * paper is still there and must still show its pictures. When the images cannot go, the rows are gone anyway (what the
 * family asked for) and the leftover objects are logged once, at ERROR, for a sweep.
 *
 * <p>Its own containers: the blocker table below must never leak into {@code PaperDeleteApiTest}'s schema sweep.
 */
@Testcontainers
class PaperDeleteOrderTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private static final String SERVICE_LOGGER = "com.elioo.baymax.web.application.service";

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    private static ReactiveWebApplicationContextRunner runner() {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, PaintedPaper.NOW_REPLY, "Potassium", "11.0")
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.urgency.sweep-cron=-",
                        "baymax.auth.hmac-secret=paper-delete-order-secret", "baymax.web.public-origin=" + AppApiContext.ORIGIN);
    }

    @Test
    void whenTheRowsCannotBeDeletedTheImagesStay() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Paper p = paper(context, "+8801700000481");
            String page = StorageKeys.page(p.patient.familyId(), p.patient.id(), p.document, 1);
            StoragePort bucket = context.getBean(StoragePort.class);
            String stored = sha256(bucket.get(page).block());
            try {
                sql("CREATE TABLE baymax.test_delete_blocker (document_id uuid REFERENCES baymax.document(id))");
                sql("INSERT INTO baymax.test_delete_blocker VALUES ('" + p.document + "')");

                delete(p).expectStatus().is5xxServerError();

                assertThat(scalar("select count(*) from baymax.document where id = '" + p.document + "'"))
                        .as("the document row, which the blocker kept").isEqualTo("1");
                assertThat(bucket.listKeys(StorageKeys.documentPrefix(p.patient.familyId(), p.patient.id(), p.document))
                        .collectList().block()).as("page-1.jpg is gone while the document row remains").contains(page);
                assertThat(sha256(bucket.get(page).block())).as("the page's bytes, unchanged").isEqualTo(stored);
            } finally {
                sql("DROP TABLE IF EXISTS baymax.test_delete_blocker");
            }
        });
    }

    @Test
    void whenTheImagesCannotBeDeletedTheRowsAreGoneAndTheOrphanIsLogged() {
        runner().withBean(FailingDeletes.class).run(context -> {
            assertThat(context).hasNotFailed();
            Paper p = paper(context, "+8801700000482");
            Logger service = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.start();
            service.addAppender(events);
            try {
                delete(p).expectStatus().isOk().expectBody().json("{\"objects_deleted\":0}");
            } finally {
                service.detachAppender(events);
            }

            try (Connection c = connect()) {
                assertThat(DeletionSweep.tablesStillHolding(c, p.document)).as("tables still holding the paper").isEmpty();
            }
            List<ILoggingEvent> errors = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).as("ERROR events from the delete").hasSize(1);
            ILoggingEvent orphan = errors.get(0);
            assertThat(orphan.getFormattedMessage()).as("the orphan's marker").contains("orphaned_objects");
            assertThat(Arrays.asList(orphan.getArgumentArray())).as("the orphan line names the paper by id").contains(p.document);
        });
    }

    /** The real bucket, except that emptying a prefix fails: the storage outage after the rows have gone. */
    @Primary
    static final class FailingDeletes implements StoragePort {
        private final S3StorageAdapter real;

        FailingDeletes(S3StorageAdapter real) {
            this.real = real;
        }

        @Override
        public Mono<Long> put(String key, byte[] bytes, String contentType) {
            return real.put(key, bytes, contentType);
        }

        @Override
        public Mono<byte[]> get(String key) {
            return real.get(key);
        }

        @Override
        public Flux<String> listKeys(String prefix) {
            return real.listKeys(prefix);
        }

        @Override
        public Mono<URI> signedGetUrl(String key, Duration ttl) {
            return real.signedGetUrl(key, ttl);
        }

        @Override
        public Mono<Long> deleteByPrefix(String prefix) {
            return Mono.error(new IllegalStateException("bucket unavailable"));
        }
    }

    private record Paper(WebTestClient client, String session, PatientProfile patient, UUID document) {
    }

    /** A received paper with its one page stored through the storage service (ledger row and object), as intake stores it. */
    private static Paper paper(ApplicationContext context, String number) {
        FamilyAccount family = AppApiContext.family(context, number, "Rahim");
        PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
        UUID document = context.getBean(DocumentRecordPort.class).create(Document.received(ma.id(), ma.familyId(), 1, Instant.now())).block().id();
        context.getBean(DocumentStorageUseCase.class).storePage(ma.familyId(), ma.id(), document, 1, PaintedPaper.paintedPage()).block();
        return new Paper(WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build(),
                AppApiContext.session(context, family.id()), ma, document);
    }

    private static WebTestClient.ResponseSpec delete(Paper p) {
        @SuppressWarnings("unchecked")
        Map<String, Object> token = p.client.get().uri("/app/api/documents/" + p.document + "/delete-token").cookie("baymax_session", p.session)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return p.client.method(HttpMethod.DELETE).uri("/app/api/documents/" + p.document).cookie("baymax_session", p.session)
                .header(AppApiGuard.HEADER, "1").header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("confirm_token", token.get("confirm_token"))).exchange();
    }

    private static String sha256(byte[] bytes) throws Exception {
        assertThat(bytes).as("the object's bytes").isNotEmpty();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); var rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
