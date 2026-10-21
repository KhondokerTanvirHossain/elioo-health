package com.elioo.baymax.storage;

import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 2 plan D4b: every stored image's width and height, read from its header when it is stored and backfilled on its
 * paper's first read for a row written before the columns existed. Real Postgres (the CHECK is the rule), real MinIO
 * (the bytes are what the size is checked against) and the real pipeline, with OCR and the model stubbed over
 * {@link PaintedPaper}. Every size is compared with a full decode of the object itself, in the test only, and every crop
 * with the painted colour it must show: a size that merely exists proves nothing (DR-12).
 */
@Testcontainers
class StoredDimensionsTest {

    private static final String CONSTRAINT = "stored_object_dimensions_whole";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = TestImages.minioContainer();

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    private static ReactiveWebApplicationContextRunner runner() {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, new ExtractionPipelineAcceptanceTest.StubReply(
                        PaintedPaper.NOW_REPLY, List.of("Potassium", "11.0")))
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.urgency.sweep-cron=-",
                        "baymax.auth.hmac-secret=stored-dimensions-test-secret", "baymax.auth.cookie-secure=false",
                        "baymax.free.max-docs-per-month=50", "baymax.web.public-origin=https://medioo.eliooo.org");
    }

    // ---- the tests ----------------------------------------------------------------------------------------------

    @Test
    void aRealCropsDecodedSizeIsItsStoredSize() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Paper paper = paperOf(context, "+8801700000481");
            List<Row> rows = rows(paper.document);
            assertThat(rows).as("the read paper's page").anyMatch(r -> r.kind.equals("page"));
            assertThat(rows).as("the read paper's crop").anyMatch(r -> r.kind.equals("crop"));

            for (Row row : rows) {
                BufferedImage decoded = decoded(context, row.key);
                assertThat(new Integer[]{row.width, row.height}).as("(width_px, height_px) of " + row.kind + " " + row.key)
                        .containsExactly(decoded.getWidth(), decoded.getHeight());
                assertThat(row.width).as("width_px of " + row.key).isPositive();
                assertThat(row.height).as("height_px of " + row.key).isPositive();
                if (row.kind.equals("crop")) {
                    assertThat(orangeShare(decoded)).as("share of the crop's pixels that are the reading's painted orange: "
                            + row.key).isGreaterThanOrEqualTo(0.9);
                    assertThat(row.width).as("a crop is smaller than its page").isLessThan(PaintedPaper.PAGE_W);
                }
            }
        });
    }

    @Test
    void anUndecodableObjectIsStoredWithoutDimensionsNeverZero() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            UUID document = UUID.randomUUID();
            // the JPEG magic (FF D8 FF), so the store reaches the ledger; nothing after it decodes
            byte[] undecodable = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};
            assertThatCode(() -> context.getBean(DocumentStorageUseCase.class).storeCrop(family, patient, document, "x", undecodable).block())
                    .as("storing a crop whose header does not read").doesNotThrowAnyException();

            List<Row> rows = rows(document);
            assertThat(rows).as("the undecodable crop's ledger row").hasSize(1);
            assertThat(rows.get(0).width).as("width_px").isNull();
            assertThat(rows.get(0).height).as("height_px").isNull();
        });
    }

    @Test
    void theConstraintRefusesAZeroOrAHalfDimension() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            UUID document = UUID.randomUUID();
            String key = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + document + "/crop-v1.jpg";
            try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.stored_object (family_id, "
                    + "patient_id, document_id, kind, item_id, storage_key, content_type, size_bytes) values (?, ?, ?, 'crop', 'v1', ?, "
                    + "'image/jpeg', 100)")) {
                s.setObject(1, UUID.randomUUID());
                s.setObject(2, UUID.randomUUID());
                s.setObject(3, document);
                s.setString(4, key);
                s.executeUpdate();
            }

            assertThatThrownBy(() -> setSize(key, 0, 10)).as("a zero width").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatThrownBy(() -> setSize(key, 10, null)).as("a width without a height").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatThrownBy(() -> setSize(key, null, 10)).as("a height without a width").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatCode(() -> setSize(key, 10, 20)).as("a whole size").doesNotThrowAnyException();
            assertThatCode(() -> setSize(key, null, null)).as("no size at all").doesNotThrowAnyException();
        });
    }

    @Test
    void aRowWrittenBeforeTheMigrationGetsItsDimensionsOnFirstRead() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Paper paper = paperOf(context, "+8801700000482");
            // every row of the paper as a row written before the columns existed
            execute("update baymax.stored_object set width_px = null, height_px = null where document_id = ?", paper.document);
            Row crop = rows(paper.document).stream().filter(r -> r.kind.equals("crop")).findFirst().orElseThrow();
            assertThat(crop.width).as("width_px before the first read").isNull();

            JsonNode value = valueWithCrop(read(paper), paper.document, crop.key);
            BufferedImage decoded = decoded(context, crop.key);
            assertThat(value.path("crop").path("width").isNull()).as("crop.width on the first read").isFalse();
            assertThat(value.path("crop").path("width").asInt()).as("crop.width").isEqualTo(decoded.getWidth());
            assertThat(value.path("crop").path("height").asInt()).as("crop.height").isEqualTo(decoded.getHeight());

            for (Row row : rows(paper.document)) {
                BufferedImage stored = decoded(context, row.key);
                assertThat(new Integer[]{row.width, row.height}).as("the row of " + row.kind + " " + row.key + " after the first read")
                        .containsExactly(stored.getWidth(), stored.getHeight());
            }
        });
    }

    @Test
    void aStoredSizeIsNeverRewrittenOnRead() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Paper paper = paperOf(context, "+8801700000483");
            Row crop = rows(paper.document).stream().filter(r -> r.kind.equals("crop")).findFirst().orElseThrow();
            assertThat(decoded(context, crop.key).getWidth()).as("the real crop is not 1 px wide, so (1, 1) is told apart").isGreaterThan(1);
            setSize(crop.key, 1, 1);

            JsonNode value = valueWithCrop(read(paper), paper.document, crop.key);
            assertThat(value.path("crop").path("width").asInt()).as("crop.width").isEqualTo(1);
            assertThat(value.path("crop").path("height").asInt()).as("crop.height").isEqualTo(1);
            Row after = rows(paper.document).stream().filter(r -> r.key.equals(crop.key)).findFirst().orElseThrow();
            assertThat(new Integer[]{after.width, after.height}).as("the row after the read").containsExactly(1, 1);
        });
    }

    // ---- the paper ----------------------------------------------------------------------------------------------

    record Paper(UUID document, String session, WebTestClient client) {
    }

    record Row(String kind, String key, Integer width, Integer height) {
    }

    /** A family, its patient, a session, and the painted page uploaded through the web and read to DONE. */
    private static Paper paperOf(ApplicationContext context, String number) throws Exception {
        Instant now = Instant.now();
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FAMILY, now, now)).block();
        PatientProfile patient = records.createPatient(new PatientProfile(null, family.id(), "Ma", 64, PatientProfile.Sex.FEMALE,
                List.of(), now, now)).block();
        String token = "tok-" + UUID.randomUUID();
        context.getBean(SessionStorePort.class).create(PhoneHasher.sha256(token), family.id(), now, now.plus(Duration.ofDays(1))).block();
        WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", patient.id().toString());
        body.part("upload_key", UUID.randomUUID().toString());
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        String location = client.post().uri("/app/add").cookie("baymax_session", token)
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).as("the upload's redirect").startsWith("/app/documents/");
        Paper paper = new Paper(UUID.fromString(location.substring(location.lastIndexOf('/') + 1)), token, client);
        waitForDone(paper);
        return paper;
    }

    private static void waitForDone(Paper paper) throws InterruptedException {
        String seen = null;
        for (int i = 0; i < 60; i++) {
            seen = paper.client.get().uri("/app/api/documents/" + paper.document + "/status").cookie("baymax_session", paper.session).exchange()
                    .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("status").toString();
            if ("DONE".equals(seen)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the paper never reached DONE in 30 s; last seen " + seen);
    }

    private JsonNode read(Paper paper) throws IOException {
        byte[] body = paper.client.get().uri("/app/api/documents/" + paper.document).cookie("baymax_session", paper.session).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return json.readTree(body);
    }

    /** The one value whose crop is this ledger row's, paired by the href its storage key gives, never by position. */
    private static JsonNode valueWithCrop(JsonNode paper, UUID document, String storageKey) {
        String src = ImageRefs.crop(document, storageKey);
        List<JsonNode> matches = new ArrayList<>();
        paper.path("values").forEach(v -> {
            if (src.equals(v.path("crop").path("src").asText())) {
                matches.add(v);
            }
        });
        assertThat(matches).as("the value whose crop is " + storageKey).hasSize(1);
        return matches.get(0);
    }

    // ---- the bytes and the rows ---------------------------------------------------------------------------------

    /** A full decode, in the test only: what the stored size must equal. */
    private static BufferedImage decoded(ApplicationContext context, String key) throws IOException {
        byte[] bytes = context.getBean(StoragePort.class).get(key).block();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("the object at " + key + " decodes").isNotNull();
        return image;
    }

    private static double orangeShare(BufferedImage image) {
        long orange = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (PaintedPaper.colourAt(image, (x + 0.5) / image.getWidth(), (y + 0.5) / image.getHeight()).equals(PaintedPaper.WORDS)) {
                    orange++;
                }
            }
        }
        return (double) orange / ((long) image.getWidth() * image.getHeight());
    }

    private static List<Row> rows(UUID document) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "select kind, storage_key, width_px, height_px from baymax.stored_object where document_id = ? order by storage_key")) {
            s.setObject(1, document);
            List<Row> rows = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    rows.add(new Row(r.getString(1), r.getString(2), r.getObject(3, Integer.class), r.getObject(4, Integer.class)));
                }
            }
            return rows;
        }
    }

    private static void setSize(String key, Integer width, Integer height) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "update baymax.stored_object set width_px = ?, height_px = ? where storage_key = ?")) {
            s.setObject(1, width, java.sql.Types.INTEGER);
            s.setObject(2, height, java.sql.Types.INTEGER);
            s.setString(3, key);
            assertThat(s.executeUpdate()).as("rows updated for " + key).isEqualTo(1);
        }
    }

    private static void execute(String statement, UUID document) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(statement)) {
            s.setObject(1, document);
            s.executeUpdate();
        }
    }

    /** The check constraint Postgres names in a refusal (SQLSTATE 23514), read from its own report; null for any other error. */
    private static String constraintOf(Throwable e) {
        if (!(e instanceof SQLException sql) || !"23514".equals(sql.getSQLState())) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("violates check constraint \"([^\"]+)\"").matcher(sql.getMessage());
        return m.find() ? m.group(1) : null;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
