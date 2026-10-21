package com.elioo.baymax.extraction;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.CropVerifier;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6 step 3, end to end: a blood-pressure line read through extraction (OCR → model → crop → object storage →
 * rows, {@link ExtractionPipelineAcceptanceTest}'s harness, real Postgres and MinIO) is one pair on the স্বাস্থ্য read.
 *
 * <p>The guard this class exists for: production gives each value its own crop ({@code crop-v1.jpg},
 * {@code crop-v2.jpg}) even when both are cut from the same line, so a pair keyed on the crop would be empty on every
 * real paper while a test that builds the rows by hand with a shared key stays green. Here the rows are the ones
 * extraction wrote; the pair can only come from the OCR line it stored.
 */
@Testcontainers
class BloodPressurePairAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    /** OCR reads one line, {@code BP : 130 / 80}: offsets B=0 … "130"=5–8, "80"=11–13, the line 0–13. */
    private static final List<String> WORDS = List.of("BP", ":", "130", "/", "80");

    /**
     * The systolic's span covers {@code BP : 130} (a claimed span that carries its text); the diastolic's covers only
     * {@code 80}, so it is found on its named line: two different paths through the cutter, one line.
     */
    private static final String BP_REPLY = """
            {"document_type":"lab_report","document_date":"2026-10-14","facility":"Popular",
             "values":[
               {"name":"BP","canonical_name":"bp_systolic","value":"130","unit":"mmHg",
                "source_span":{"page":1,"start":0,"end":8}},
               {"name":"BP","canonical_name":"bp_diastolic","value":"80","unit":"mmHg",
                "source_span":{"page":1,"start":11,"end":13}}%s],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],
                                 "diagnosis":[],"investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.93,"values":0.93,"medicines":1.0,"follow_up":1.0,
                           "clinical_context":1.0}}
            """;

    /** A value OCR never produced (its span points past the text), with the model's region on the page image. */
    private static final String REGION_VALUE = """
            ,
               {"name":"Pulse","value":"72","unit":"bpm",
                "source_span":{"page":1,"start":900,"end":950},
                "source_region":{"page":1,"left":0.6,"top":0.5,"right":0.8,"bottom":0.6}}""";

    private static final java.util.concurrent.atomic.AtomicInteger FAMILIES = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * markers.properties' two blood-pressure rows, as written there (production imports the file; a context runner does
     * not), so the values are canonicalised exactly as in production.
     */
    private static final String[] MARKERS = {
            "baymax.markers[0].canonical=bp_systolic", "baymax.markers[0].aliases=systolic,bp systolic,systolic bp,sbp",
            "baymax.markers[1].canonical=bp_diastolic", "baymax.markers[1].aliases=diastolic,bp diastolic,diastolic bp,dbp"};

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    @Test
    void aBloodPressureLineReadThroughExtractionIsOnePair() {
        ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO,
                new ExtractionPipelineAcceptanceTest.StubReply(BP_REPLY.formatted(""), WORDS)).withPropertyValues(MARKERS).run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            Document created = context.getBean(DocumentRecordPort.class).create(Document.received(patient, family, 1, Instant.now())).block();
            Document done = context.getBean(DocumentExtractionService.class).process(created, List.of(pageJpeg())).block();
            assertThat(done.status()).isEqualTo(Document.Status.DONE);

            List<String[]> stored = rows("SELECT canonical_name, value, crop_key, source_line FROM baymax.observation WHERE document_id = '"
                    + created.id() + "' ORDER BY canonical_name DESC");
            assertThat(stored).extracting(r -> r[0] + "=" + r[1]).containsExactly("bp_systolic=130", "bp_diastolic=80");
            // the guard: this is the shape production writes — one crop per value, never a shared key
            assertThat(stored.get(0)[2]).isNotEqualTo(stored.get(1)[2]);
            assertThat(stored).extracting(r -> r[3]).containsExactly("1:0-13", "1:0-13");

            HealthUseCase.HealthLabs labs = context.getBean(HealthUseCase.class).labs(family, patient).block();
            assertThat(labs.bp().points()).hasSize(1);
            LabSeries.BpPoint pair = labs.bp().points().get(0);
            assertThat(pair.systolic().value()).isEqualTo("130");
            assertThat(pair.diastolic().value()).isEqualTo("80");
            assertThat(pair.documentId()).isEqualTo(created.id());
            assertThat(labs.series()).as("a paired reading is not also two single series").isEmpty();
        });
    }

    /** A value recovered from the page image has no OCR line, so it can never be paired by one. */
    @Test
    void aRegionRecoveredValueHasNoLine() {
        ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO,
                        new ExtractionPipelineAcceptanceTest.StubReply(BP_REPLY.formatted(REGION_VALUE), WORDS))
                .withPropertyValues(MARKERS).withPropertyValues("baymax.extract.recover-crops-from-image=true")
                // the independent re-read confirms the region (the real one would call Vision on the crop)
                .withBean("confirmingCropVerifier", CropVerifier.class,
                        () -> new CropVerifier((documentId, crop) -> Mono.just("72"), (documentId, crop) -> Mono.empty()),
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    UUID family = UUID.randomUUID();
                    UUID patient = UUID.randomUUID();
                    seedFamily(family, patient);
                    Document created = context.getBean(DocumentRecordPort.class).create(Document.received(patient, family, 1, Instant.now())).block();
                    Document done = context.getBean(DocumentExtractionService.class).process(created, List.of(pageJpeg())).block();
                    assertThat(done.status()).isEqualTo(Document.Status.DONE);

                    List<String[]> stored = rows("SELECT name, value, source_line FROM baymax.observation WHERE document_id = '"
                            + created.id() + "' ORDER BY name, value");
                    assertThat(stored).extracting(r -> r[0] + "=" + r[1]).containsExactly("BP=130", "BP=80", "Pulse=72");
                    assertThat(stored.get(2)[2]).as("the region-recovered value's line").isNull();
                    assertThat(stored.get(0)[2]).as("an OCR value's line, in the same run").isEqualTo("1:0-13");
                });
    }

    private static byte[] pageJpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private static void seedFamily(UUID family, UUID patient) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("insert into baymax.family_account (id, whatsapp_number, owner_name, terms_accepted_at) values ('"
                    + family + "','+8801700000" + String.format("%03d", 600 + FAMILIES.incrementAndGet()) + "','o', now())");
            s.execute("insert into baymax.patient_profile (id, family_id, name, age, sex, proxy_consent_at) values ('"
                    + patient + "','" + family + "','p',70,'female', now())");
        }
    }

    private static List<String[]> rows(String query) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) {
                    row[i] = rs.getString(i + 1);
                }
                out.add(row);
            }
        }
        return out;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
