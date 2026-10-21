package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.PaintedPaper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B4b step 6: one press per key, on Postgres. The replay rule lives in intake ({@code DocumentIntakeService.pressKey}
 * and {@code samePatient}) and in V21's unique key, so it is exercised against a real database through the JSON route,
 * never only a mocked port (CLAUDE.md: a mock cannot fail the way production fails). Both directions: the same press
 * twice is one document, and the same key with other files is a new press, never merged into the first.
 */
@Testcontainers
class UploadApiReplayTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private static final String KEY = "3f2a9c1e-6b7d-4e8f-9a0b-1c2d3e4f5a60";

    /** The real module over these containers, OCR and the model stubbed to read the painted page as NOW. */
    private static ReactiveWebApplicationContextRunner runner() {
        return AppApiContext.runner(POSTGRES, MINIO);
    }

    private static EntityExchangeResult<byte[]> press(WebTestClient client, String token, UUID patient, String key, byte[] page) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", patient.toString());
        body.part("upload_key", key);
        body.part("file", page).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        return client.post().uri("/app/api/uploads").cookie("baymax_session", token).header("X-Medioo", "1")
                .header("Origin", AppApiContext.ORIGIN).body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectBody().returnResult();
    }

    /** One press of several pages, each its own document ("আলাদা আলাদা"). */
    private static EntityExchangeResult<byte[]> pressSeparate(WebTestClient client, String token, UUID patient, String key, byte[]... pages) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", patient.toString());
        body.part("upload_key", key);
        body.part("group", "separate");
        for (int i = 0; i < pages.length; i++) {
            body.part("file", pages[i]).filename("page-" + (i + 1) + ".jpg").contentType(MediaType.IMAGE_JPEG);
        }
        return client.post().uri("/app/api/uploads").cookie("baymax_session", token).header("X-Medioo", "1")
                .header("Origin", AppApiContext.ORIGIN).body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectBody().returnResult();
    }

    /** Every {@code documents[*].id} of a 200, in order, read from the body by its field. */
    private static List<String> ids(EntityExchangeResult<byte[]> r) {
        assertThat(r.getStatus().value()).as("the press answered " + body(r)).isEqualTo(200);
        Matcher m = Pattern.compile("\\{\"id\":\"([0-9a-f-]{36})\"\\}").matcher(body(r));
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** {@code next} of a 200, read by its field. */
    private static String next(EntityExchangeResult<byte[]> r) {
        Matcher m = Pattern.compile("\"next\":\"([^\"]*)\"").matcher(body(r));
        assertThat(m.find()).as("next in " + body(r)).isTrue();
        return m.group(1);
    }

    private static String body(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    /** {@code documents[0].id} of a 200, read from the body by its field. */
    private static String firstId(EntityExchangeResult<byte[]> r) {
        assertThat(r.getStatus().value()).as("the press answered " + body(r)).isEqualTo(200);
        Matcher m = Pattern.compile("\"documents\":\\[\\{\"id\":\"([0-9a-f-]{36})\"").matcher(body(r));
        assertThat(m.find()).as("documents[0].id in " + body(r)).isTrue();
        return m.group(1);
    }

    @Test
    void theSamePressSentTwiceIsOneDocument() {
        runner().run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000611", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            byte[] page = PaintedPaper.paintedPage();

            String first = firstId(press(client, token, ma.id(), KEY, page));
            String second = firstId(press(client, token, ma.id(), KEY, page));

            assertThat(second).as("the retry's id").isEqualTo(first);
            assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'")).isEqualTo(1);
            assertThat(count("select count(*) from baymax.audit_event where kind = 'document_upload' and patient_id = '" + ma.id() + "'"))
                    .as("the replay recorded no second upload").isEqualTo(1);
        });
    }

    /** The dangerous direction: a sheet restored by Back keeps its key, and other files on it are a new paper. */
    @Test
    void theSameKeyWithOtherFilesIsANewPressNeverMerged() {
        runner().run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000612", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            String first = firstId(press(client, token, ma.id(), KEY, PaintedPaper.paintedPage()));
            String second = firstId(press(client, token, ma.id(), KEY, otherPage(GREEN)));

            assertThat(second).as("another paper under the same key").isNotEqualTo(first);
            assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'")).isEqualTo(2);
        });
    }

    /**
     * "আলাদা আলাদা" goes through its own intake path ({@code acceptEach}: a key per file, the free allowance counted
     * over the files still missing), so its replay is exercised against Postgres too, never only a mock that answers
     * what it is told. The same two pages under the same key are the same two documents, in the same order, with the
     * same {@code next}, and the replay records no second upload.
     */
    @Test
    void theSameSeparatePressSentTwiceIsTheSameDocuments() {
        runner().run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000614", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            byte[] one = PaintedPaper.paintedPage();
            byte[] two = otherPage(GREEN);

            EntityExchangeResult<byte[]> first = pressSeparate(client, token, ma.id(), KEY, one, two);
            EntityExchangeResult<byte[]> retry = pressSeparate(client, token, ma.id(), KEY, one, two);

            assertThat(ids(first)).as("one document per page").hasSize(2).doesNotHaveDuplicates();
            assertThat(ids(retry)).as("the retry's ids, in order").containsExactlyElementsOf(ids(first));
            assertThat(next(first)).isEqualTo("/app/timeline?patient=" + ma.id());
            assertThat(next(retry)).isEqualTo(next(first));
            assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'")).isEqualTo(2);
            assertThat(count("select count(*) from baymax.audit_event where kind = 'document_upload' and patient_id = '" + ma.id() + "'"))
                    .as("the replay recorded no second upload").isEqualTo(2);
        });
    }

    /**
     * The dangerous direction for "আলাদা আলাদা": the same key with one page changed is a new press (D3: the press is
     * keyed by the key AND every file sent with it), so both of its pages are new documents and neither of the first
     * press's is handed back for a page that was not in it.
     */
    @Test
    void theSameKeyWithOnePageChangedIsANewSeparatePress() {
        runner().run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000615", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            byte[] one = PaintedPaper.paintedPage();

            List<String> first = ids(pressSeparate(client, token, ma.id(), KEY, one, otherPage(GREEN)));
            List<String> changed = ids(pressSeparate(client, token, ma.id(), KEY, one, otherPage(GREY)));

            assertThat(changed).as("the changed press's documents").hasSize(2).doesNotContainAnyElementsOf(first);
            assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'")).isEqualTo(4);
        });
    }

    /**
     * The same key AND the same bytes for another patient: the press exists, but it is Ma's, and Baba never lands on it.
     * The same key with other bytes for Baba is a new press (the test above), so intake is not "fixed" to refuse on the
     * key alone.
     */
    @Test
    void theSameKeyForAnotherPatientIsRefused() {
        runner().run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000613", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            PatientProfile baba = AppApiContext.patient(context, family.id(), "Baba", 70, PatientProfile.Sex.MALE);
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            byte[] page = PaintedPaper.paintedPage();

            firstId(press(client, token, ma.id(), KEY, page));
            String both = "select count(*) from baymax.document where patient_id in ('" + ma.id() + "','" + baba.id() + "')";
            int before = count(both);

            EntityExchangeResult<byte[]> refused = press(client, token, baba.id(), KEY, page);
            assertThat(refused.getStatus().value()).as(body(refused)).isEqualTo(409);
            assertThat(body(refused)).isEqualTo("{\"reason\":\"UPLOAD_KEY_REUSED\"}");
            assertThat(count(both)).as("documents over both patients").isEqualTo(before);
        });
    }

    private static final Color GREEN = new Color(20, 160, 60);
    private static final Color GREY = new Color(120, 120, 120);

    /** A different picture of the same size, all one colour: other bytes, still a page intake reads. */
    private static byte[] otherPage(Color colour) throws Exception {
        BufferedImage image = new BufferedImage(PaintedPaper.PAGE_W, PaintedPaper.PAGE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, PaintedPaper.PAGE_W, PaintedPaper.PAGE_H);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static int count(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
