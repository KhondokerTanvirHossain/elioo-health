package com.elioo.baymax.storage;

import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.adapter.out.s3.S3StorageAdapter;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 2 plan D4b: every stored image's width and height, read from its header when it is stored and backfilled on its
 * paper's first read for a crop row written before the columns existed. Real Postgres (the CHECKs are the rule), real
 * MinIO (the bytes are what the size is checked against) and the real pipeline, with OCR and the model stubbed over
 * {@link PaintedPaper}. Every size is compared with a full decode of the object itself, in the test only, and every crop
 * with the painted colour it must show: a size that merely exists proves nothing (DR-12). "Measured once, never again" is
 * counted on the store's own GETs ({@link Counted}), never inferred from the rows.
 */
@Testcontainers
class StoredDimensionsTest {

    private static final String CONSTRAINT = "stored_object_dimensions_whole";
    private static final String CHECKED = "stored_object_dimensions_checked";
    /** The JPEG magic (FF D8 FF), so a store reaches the ledger; nothing after it decodes. */
    private static final byte[] UNDECODABLE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};

    /**
     * Three values, each with its own crop: Potassium 11.0, Sodium 130 and Chloride 90 on one line (synthetic, DR-36), so
     * one paper can hold a crop whose object is gone, one whose bytes do not decode, and one that measures.
     */
    static final String THREE_VALUES = """
            {"document_type":"lab_report","document_date":"2026-10-12","facility":"Shapla Diagnostics",
             "values":[{"name":"Potassium","value":"11.0","unit":"mmol/L","ref_low":"3.5","ref_high":"5.1",
                        "source_span":{"page":1,"start":0,"end":14}},
                       {"name":"Sodium","value":"130","unit":"mmol/L","ref_low":"135","ref_high":"145",
                        "source_span":{"page":1,"start":15,"end":25}},
                       {"name":"Chloride","value":"90","unit":"mmol/L","ref_low":"98","ref_high":"107",
                        "source_span":{"page":1,"start":26,"end":37}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                                 "investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.95,"values":0.95,"medicines":1.0,"follow_up":1.0,"clinical_context":1.0}}
            """;

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
        return runner(new ExtractionPipelineAcceptanceTest.StubReply(PaintedPaper.NOW_REPLY, List.of("Potassium", "11.0")));
    }

    private static ReactiveWebApplicationContextRunner threeValues() {
        return runner(new ExtractionPipelineAcceptanceTest.StubReply(THREE_VALUES,
                List.of("Potassium", "11.0", "Sodium", "130", "Chloride", "90")));
    }

    private static ReactiveWebApplicationContextRunner runner(ExtractionPipelineAcceptanceTest.StubReply reply) {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, reply)
                .withUserConfiguration(Counted.class)
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.urgency.sweep-cron=-",
                        "baymax.auth.hmac-secret=stored-dimensions-test-secret", "baymax.auth.cookie-secure=false",
                        "baymax.free.max-docs-per-month=50", "baymax.web.public-origin=https://medioo.eliooo.org");
    }

    // ---- at store time ------------------------------------------------------------------------------------------

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
                assertThat(row.checked).as("dimensions_checked_at of " + row.key).isTrue();
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
            assertThatCode(() -> context.getBean(DocumentStorageUseCase.class).storeCrop(family, patient, document, "x", UNDECODABLE).block())
                    .as("storing a crop whose header does not read").doesNotThrowAnyException();

            List<Row> rows = rows(document);
            assertThat(rows).as("the undecodable crop's ledger row").hasSize(1);
            assertThat(rows.get(0).width).as("width_px").isNull();
            assertThat(rows.get(0).height).as("height_px").isNull();
            assertThat(rows.get(0).checked).as("dimensions_checked_at: tried at store time, so no read fetches it").isTrue();
        });
    }

    @Test
    void theConstraintRefusesAZeroOrAHalfDimension() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            String key = insertCrop(UUID.randomUUID(), "v1");

            assertThatThrownBy(() -> setSize(key, 0, 10)).as("a zero width").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatThrownBy(() -> setSize(key, 10, null)).as("a width without a height").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatThrownBy(() -> setSize(key, null, 10)).as("a height without a width").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CONSTRAINT));
            assertThatThrownBy(() -> execute("update baymax.stored_object set width_px = 10, height_px = 20 where storage_key = ?", key))
                    .as("a size on a row never tried, not recorded by the read that produced it").isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(constraintOf(e)).isEqualTo(CHECKED));
            assertThatCode(() -> setSize(key, 10, 20)).as("a whole size, tried").doesNotThrowAnyException();
            assertThatCode(() -> setSize(key, null, null)).as("no size at all").doesNotThrowAnyException();
        });
    }

    // ---- on a paper's read --------------------------------------------------------------------------------------

    @Test
    void aRowWrittenBeforeTheMigrationGetsItsDimensionsOnFirstRead() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Paper paper = paperOf(context, "+8801700000482");
            legacy(paper.document);
            Row crop = rows(paper.document).stream().filter(r -> r.kind.equals("crop")).findFirst().orElseThrow();

            JsonNode value = valueWithCrop(read(paper), paper.document, crop.key);
            BufferedImage decoded = decoded(context, crop.key);
            assertThat(value.path("crop").path("width").isNull()).as("crop.width on the first read").isFalse();
            assertThat(value.path("crop").path("width").asInt()).as("crop.width").isEqualTo(decoded.getWidth());
            assertThat(value.path("crop").path("height").asInt()).as("crop.height").isEqualTo(decoded.getHeight());

            for (Row row : rows(paper.document)) {
                if (row.kind.equals("crop")) {
                    BufferedImage stored = decoded(context, row.key);
                    assertThat(new Integer[]{row.width, row.height}).as("the row of crop " + row.key + " after the first read")
                            .containsExactly(stored.getWidth(), stored.getHeight());
                    assertThat(row.checked).as("dimensions_checked_at of " + row.key).isTrue();
                } else {
                    // nothing reads a page's size yet: a page (up to the upload cap) is never pulled to be measured
                    assertThat(new Object[]{row.width, row.height, row.checked}).as("the page row " + row.key + " after the read")
                            .containsExactly(null, null, false);
                }
            }
        });
    }

    /**
     * Measured once, never again, counted on the store itself: a paper stored with its sizes costs no GET on any read; a
     * legacy paper's first read costs exactly one GET per crop row and none for its page; its second read costs none.
     */
    @Test
    void aPaperIsFetchedForItsSizesOnlyOnItsFirstLegacyRead() {
        threeValues().run(context -> {
            assertThat(context).hasNotFailed();
            Counted.Store store = context.getBean(Counted.Store.class);
            Paper paper = paperOf(context, "+8801700000484");
            List<String> crops = cropKeys(paper.document);
            String page = rows(paper.document).stream().filter(r -> r.kind.equals("page")).map(Row::key).findFirst().orElseThrow();
            assertThat(crops).as("the paper's crop rows (three values, three crops)").hasSize(3);

            store.reset();
            read(paper);
            assertThat(store.gets()).as("GETs while reading a paper stored with its sizes").isEmpty();

            legacy(paper.document);
            store.reset();
            JsonNode first = read(paper);
            for (String key : crops) {
                assertThat(store.gets(key)).as("GETs of crop " + key + " on the legacy paper's first read").isEqualTo(1);
                assertThat(valueWithCrop(first, paper.document, key).path("crop").path("width").isNull())
                        .as("crop.width of " + key + " on the first read").isFalse();
            }
            assertThat(store.gets(page)).as("GETs of the page on the first read").isZero();
            assertThat(store.gets().keySet()).as("keys fetched on the first read").containsExactlyInAnyOrderElementsOf(crops);

            store.reset();
            read(paper);
            assertThat(store.gets()).as("GETs on the second read of a backfilled paper").isEmpty();
        });
    }

    /**
     * A legacy paper with one crop whose object is gone and one whose bytes do not decode is still sent, with those two
     * crops sized null and the other crop backfilled; the two are recorded as tried, so the next read fetches neither.
     */
    @Test
    void aMissingOrUndecodableObjectLeavesThePaperReadable() {
        threeValues().run(context -> {
            assertThat(context).hasNotFailed();
            Counted.Store store = context.getBean(Counted.Store.class);
            Paper paper = paperOf(context, "+8801700000485");
            List<String> crops = cropKeys(paper.document);
            assertThat(crops).as("the paper's crop rows").hasSize(3);
            String gone = crops.get(0);
            String corrupt = crops.get(1);
            String good = crops.get(2);
            legacy(paper.document);
            assertThat(store.delegate.deleteByPrefix(gone).block()).as("objects deleted under " + gone).isEqualTo(1L);
            store.delegate.put(corrupt, UNDECODABLE, "image/jpeg").block();

            store.reset();
            JsonNode body = read(paper);
            for (String key : List.of(gone, corrupt)) {
                JsonNode crop = valueWithCrop(body, paper.document, key).path("crop");
                assertThat(crop.has("width") && crop.path("width").isNull()).as("crop.width of " + key + " is JSON null").isTrue();
                assertThat(crop.has("height") && crop.path("height").isNull()).as("crop.height of " + key + " is JSON null").isTrue();
                Row row = row(paper.document, key);
                assertThat(new Integer[]{row.width, row.height}).as("the row of " + key).containsExactly(null, null);
                assertThat(row.checked).as("dimensions_checked_at of " + key + ": tried, so never fetched again").isTrue();
            }
            JsonNode measured = valueWithCrop(body, paper.document, good).path("crop");
            BufferedImage decoded = decoded(context, good);
            assertThat(new int[]{measured.path("width").asInt(), measured.path("height").asInt()}).as("the crop that measures")
                    .containsExactly(decoded.getWidth(), decoded.getHeight());
            Row goodRow = row(paper.document, good);
            assertThat(new Integer[]{goodRow.width, goodRow.height}).as("its row").containsExactly(decoded.getWidth(), decoded.getHeight());

            store.reset();
            read(paper);
            assertThat(store.gets()).as("GETs on the next read: an unreadable object is tried once ever").isEmpty();
        });
    }

    /**
     * A store that never answers holds the paper no longer than the read budget: it is sent with that crop unsized, the
     * others measured, and the stuck row is left untried for a later read rather than recorded as unreadable.
     */
    @Test
    void aStoreThatNeverAnswersDoesNotHoldThePaper() {
        threeValues().run(context -> {
            assertThat(context).hasNotFailed();
            Counted.Store store = context.getBean(Counted.Store.class);
            Paper paper = paperOf(context, "+8801700000486");
            List<String> crops = cropKeys(paper.document);
            String stuck = crops.get(0);
            legacy(paper.document);
            store.hang.add(stuck);

            long start = System.nanoTime();
            JsonNode body = read(paper);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).as("time to send the paper with the store not answering for one crop").isLessThan(Duration.ofSeconds(5));
            JsonNode crop = valueWithCrop(body, paper.document, stuck).path("crop");
            assertThat(crop.path("width").isNull() && crop.path("height").isNull()).as("the stuck crop's width and height").isTrue();
            assertThat(row(paper.document, stuck).checked).as("the stuck row is left untried, for a later read").isFalse();
            for (String key : crops.subList(1, crops.size())) {
                assertThat(row(paper.document, key).width).as("width_px of " + key).isNotNull();
            }
        });
    }

    @Test
    void aStoredSizeIsNeverRewrittenOnRead() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Counted.Store store = context.getBean(Counted.Store.class);
            Paper paper = paperOf(context, "+8801700000483");
            Row crop = rows(paper.document).stream().filter(r -> r.kind.equals("crop")).findFirst().orElseThrow();
            assertThat(decoded(context, crop.key).getWidth()).as("the real crop is not 1 px wide, so (1, 1) is told apart").isGreaterThan(1);
            setSize(crop.key, 1, 1);

            store.reset();
            JsonNode value = valueWithCrop(read(paper), paper.document, crop.key);
            assertThat(value.path("crop").path("width").asInt()).as("crop.width").isEqualTo(1);
            assertThat(value.path("crop").path("height").asInt()).as("crop.height").isEqualTo(1);
            Row after = row(paper.document, crop.key);
            assertThat(new Integer[]{after.width, after.height}).as("the row after the read").containsExactly(1, 1);
            assertThat(store.gets()).as("GETs while reading a paper whose sizes are stored").isEmpty();
        });
    }

    /**
     * The ledger's own guard, apart from the read path's skip: a row already tried takes no size, whoever asks. This is the
     * case of two first reads racing, where both measured the row before either recorded it.
     */
    @Test
    void theLedgerNeverRewritesARowAlreadyTried() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            StoredObjectLedgerPort ledger = context.getBean(StoredObjectLedgerPort.class);
            UUID document = UUID.randomUUID();
            String sized = insertCrop(document, "s1");
            String untried = insertCrop(document, "u1");
            setSize(sized, 1, 1);

            assertThat(ledger.recordDimensions(sized, 152, 52).block()).as("rows taking a size over a stored (1, 1)").isZero();
            assertThat(ledger.recordUnmeasurable(sized).block()).as("rows marked unreadable over a stored (1, 1)").isZero();
            Row sizedRow = row(document, sized);
            assertThat(new Integer[]{sizedRow.width, sizedRow.height}).as("the sized row after both").containsExactly(1, 1);

            // the other direction: a row never tried takes the first size recorded, and only the first
            assertThat(ledger.recordDimensions(untried, 152, 52).block()).as("rows taking a first size").isEqualTo(1L);
            assertThat(ledger.recordDimensions(untried, 300, 100).block()).as("rows taking a second size").isZero();
            Row untriedRow = row(document, untried);
            assertThat(new Integer[]{untriedRow.width, untriedRow.height}).as("the row after both").containsExactly(152, 52);
            assertThat(untriedRow.checked).as("dimensions_checked_at with the size").isTrue();
        });
    }

    // ---- the paper ----------------------------------------------------------------------------------------------

    record Paper(UUID document, String session, WebTestClient client) {
    }

    record Row(String kind, String key, Integer width, Integer height, boolean checked) {
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

    /**
     * Wraps the context's store and counts every GET by key, so "measured once, never again" is asserted on the store's
     * own calls. {@link Store#delegate} is the real store, for the test's own reads and writes, which are not counted. A key
     * in {@link Store#hang} is never answered. Deliberately not {@code @Configuration}: the module's component scan covers
     * {@code com.elioo.baymax}, test classes included, and would put this into every other test's context. It is
     * registered only here, through {@code withUserConfiguration}.
     */
    static class Counted {

        @Bean
        @Primary
        Store countedStore(S3StorageAdapter real) {
            return new Store(real);
        }

        static final class Store implements StoragePort {
            final StoragePort delegate;
            final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();
            final Set<String> hang = ConcurrentHashMap.newKeySet();

            Store(StoragePort delegate) {
                this.delegate = delegate;
            }

            void reset() {
                gets.clear();
            }

            Map<String, Integer> gets() {
                Map<String, Integer> out = new TreeMap<>();
                gets.forEach((k, n) -> out.put(k, n.get()));
                return out;
            }

            int gets(String key) {
                return gets().getOrDefault(key, 0);
            }

            @Override
            public Mono<byte[]> get(String key) {
                return Mono.defer(() -> {
                    gets.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    return hang.contains(key) ? Mono.never() : delegate.get(key);
                });
            }

            @Override
            public Mono<Long> put(String key, byte[] bytes, String contentType) {
                return delegate.put(key, bytes, contentType);
            }

            @Override
            public Flux<String> listKeys(String prefix) {
                return delegate.listKeys(prefix);
            }

            @Override
            public Mono<URI> signedGetUrl(String key, Duration ttl) {
                return delegate.signedGetUrl(key, ttl);
            }

            @Override
            public Mono<Long> deleteByPrefix(String prefix) {
                return delegate.deleteByPrefix(prefix);
            }
        }
    }

    // ---- the bytes and the rows ---------------------------------------------------------------------------------

    /** A full decode, in the test only and through the real store (not counted): what the stored size must equal. */
    private static BufferedImage decoded(ApplicationContext context, String key) throws IOException {
        byte[] bytes = context.getBean(Counted.Store.class).delegate.get(key).block();
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
                "select kind, storage_key, width_px, height_px, dimensions_checked_at is not null from baymax.stored_object "
                        + "where document_id = ? order by storage_key")) {
            s.setObject(1, document);
            List<Row> rows = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    rows.add(new Row(r.getString(1), r.getString(2), r.getObject(3, Integer.class), r.getObject(4, Integer.class),
                            r.getBoolean(5)));
                }
            }
            return rows;
        }
    }

    private static List<String> cropKeys(UUID document) throws SQLException {
        return rows(document).stream().filter(r -> r.kind.equals("crop")).map(Row::key).toList();
    }

    private static Row row(UUID document, String key) throws SQLException {
        return rows(document).stream().filter(r -> r.key.equals(key)).findFirst().orElseThrow();
    }

    /** Every row of the paper as a row written before the dimension columns existed: no size, never tried. */
    private static void legacy(UUID document) throws SQLException {
        execute("update baymax.stored_object set width_px = null, height_px = null, dimensions_checked_at = null where document_id = ?",
                document);
        assertThat(rows(document)).as("the paper's rows, made legacy").isNotEmpty()
                .allMatch(r -> r.width == null && r.height == null && !r.checked);
    }

    /** A crop row with no size, never tried; returns its key. */
    private static String insertCrop(UUID document, String item) throws SQLException {
        String key = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + document + "/crop-" + item + ".jpg";
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("insert into baymax.stored_object (family_id, "
                + "patient_id, document_id, kind, item_id, storage_key, content_type, size_bytes) values (?, ?, ?, 'crop', ?, ?, "
                + "'image/jpeg', 100)")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, UUID.randomUUID());
            s.setObject(3, document);
            s.setString(4, item);
            s.setString(5, key);
            s.executeUpdate();
        }
        return key;
    }

    /** Sets a size as a read records it: with dimensions_checked_at (kept when the row already has one). */
    private static void setSize(String key, Integer width, Integer height) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("update baymax.stored_object set width_px = ?, "
                + "height_px = ?, dimensions_checked_at = coalesce(dimensions_checked_at, now()) where storage_key = ?")) {
            s.setObject(1, width, java.sql.Types.INTEGER);
            s.setObject(2, height, java.sql.Types.INTEGER);
            s.setString(3, key);
            assertThat(s.executeUpdate()).as("rows updated for " + key).isEqualTo(1);
        }
    }

    private static void execute(String statement, Object parameter) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(statement)) {
            s.setObject(1, parameter);
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
