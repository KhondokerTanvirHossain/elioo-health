package com.elioo.baymax.extraction;

import com.elioo.baymax.aicall.application.service.MeteredVisionOcr;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.healthcare.gcp.vision.model.TextBlock;
import com.elioo.healthcare.gcp.vision.model.TextGeometry;
import com.elioo.healthcare.gcp.vision.model.TextParagraph;
import com.elioo.healthcare.gcp.vision.model.TextWord;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pipeline against real infrastructure: MinIO for crops, Postgres for rows, the real wiring in
 * between. Vision and the model are stubbed at their ports, so the test needs no cloud credentials and
 * still exercises what a credentialled run would: span → crop → object storage → item rows.
 *
 * <p>This is the test that would have caught a wiring mistake in the local run, where Vision could not
 * authenticate on a developer laptop.</p>
 */
@Testcontainers
class ExtractionPipelineAcceptanceTest {

    static final String BUCKET = "baymax";
    static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

    static final String REPLY = """
            {"document_type":"lab_report","document_date":"2026-03-14","facility":"Popular",
             "values":[
               {"name":"HbA1c","value":"8.2","unit":"%","flag":"high",
                "source_span":{"page":1,"start":0,"end":5}},
               {"name":"Ghost","value":"1.0","unit":"x",
                "source_span":{"page":1,"start":900,"end":950}}],
             "medicines":[],"follow_up":[],
             "confidence":{"overall":0.93,"values":0.93,"medicines":1.0,"follow_up":1.0}}
            """;

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

    /** Stubs for the two outside services, so the run needs no GCP key and no model spend. */
    @Configuration
    static class StubbedProviders {

        @Bean
        @Primary
        MeteredVisionOcr stubOcr() {
            MeteredVisionOcr ocr = mock(MeteredVisionOcr.class);
            TextWord name = word("HbA1c", 10, 10, 90, 40);
            TextWord value = word("8.2", 100, 10, 140, 40);
            when(ocr.detectDocumentText(any(), any())).thenReturn(Mono.just(new VisionOcrResponse(
                    "HbA1c 8.2",
                    List.of(new TextBlock("", "en", 0.9f, null,
                            List.of(new TextParagraph("", 0.9f, null, List.of(name, value))), 0, "TEXT")),
                    List.of(), 0.92, null)));
            return ocr;
        }

        @Bean
        @Primary
        LlmClient stubModel() {
            LlmClient client = mock(LlmClient.class);
            when(client.providerName()).thenReturn("groq");
            when(client.supportsImages()).thenReturn(false);
            when(client.invoke(any())).thenReturn(Mono.just(new LlmResponse(REPLY, "stop",
                    new TokenUsage(1200, 300), "openai/gpt-oss-120b", null, "groq", 800L)));
            return client;
        }

        private static TextWord word(String text, int l, int t, int r, int b) {
            return new TextWord(text, 0.9f, new TextGeometry(new TextGeometry.BoundingBox(List.of(
                    new TextGeometry.Point(l, t), new TextGeometry.Point(r, t),
                    new TextGeometry.Point(r, b), new TextGeometry.Point(l, b))), List.of()));
        }
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withUserConfiguration(StubbedProviders.class)
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
    void aDocumentIsExtractedAndEveryStoredValueHasACropInTheBucket() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);

            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            DocumentExtractionService extraction = context.getBean(DocumentExtractionService.class);
            StoragePort bucket = context.getBean(StoragePort.class);

            Document created = records.create(Document.received(patient, family, 1, Instant.now())).block();
            Document done = extraction.process(created, List.of(pageJpeg())).block();

            assertThat(done.status()).isEqualTo(Document.Status.DONE);
            assertThat(done.documentType()).isEqualTo("lab_report");
            assertThat(done.modelFinal()).isEqualTo("groq/openai/gpt-oss-120b");

            // the good value survived with a crop; the one whose span points nowhere was dropped
            assertThat(scalar("select count(*) from baymax.observation where document_id='" + created.id() + "'"))
                    .isEqualTo("1");
            assertThat(scalar("select name from baymax.observation where document_id='" + created.id() + "'"))
                    .isEqualTo("HbA1c");
            String cropKey = scalar("select crop_key from baymax.observation where document_id='" + created.id() + "'");
            assertThat(cropKey).isEqualTo(family + "/" + patient + "/" + created.id() + "/crop-v1.jpg");

            // the crop is really in the bucket, and it is a JPEG
            byte[] crop = bucket.get(cropKey).block();
            assertThat(crop).isNotNull();
            assertThat((crop[0] & 0xFF)).isEqualTo(0xFF);
            assertThat((crop[1] & 0xFF)).isEqualTo(0xD8);

            // the call was costed, and the document carries the sum
            assertThat(Integer.parseInt(scalar(
                    "select count(*) from baymax.ai_call_log where document_id='" + created.id() + "'")))
                    .isGreaterThanOrEqualTo(1);
            assertThat(scalar("select status from baymax.ai_call_log where document_id='" + created.id()
                    + "' and purpose='extract'")).isEqualTo("ok");

            // cost is summed from the call log onto the document: 1200 in + 300 out at 0.15/0.60 per 1M
            assertThat(new java.math.BigDecimal(scalar(
                    "select cost_usd from baymax.document where id='" + created.id() + "'")))
                    .isEqualByComparingTo("0.00036");

            // the "Ghost" value pointed at text that is not on the page: dropped, but counted, so a family
            // is told their page was partly unreadable rather than quietly given less than they sent
            assertThat(scalar("select unverified_values from baymax.document where id='" + created.id() + "'"))
                    .isEqualTo("1");
            assertThat(scalar("select unverified_medicines + unverified_follow_up from baymax.document where id='"
                    + created.id() + "'")).isEqualTo("0");
            assertThat(done.unverified().values()).isEqualTo(1);
            assertThat(done.unverified().total()).isEqualTo(1);
        });
    }

    @Test
    void aLowConfidenceDocumentNeedsARetakeAndLeavesNoItems() {
        runner().withBean(LlmClient.class, () -> {
            LlmClient client = mock(LlmClient.class);
            when(client.providerName()).thenReturn("groq");
            when(client.invoke(any())).thenReturn(Mono.just(new LlmResponse(
                    REPLY.replace("\"overall\":0.93", "\"overall\":0.40"), "stop",
                    new TokenUsage(1200, 300), "openai/gpt-oss-120b", null, "groq", 800L)));
            return client;
        }).run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);

            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            Document created = records.create(Document.received(patient, family, 1, Instant.now())).block();
            Document done = context.getBean(DocumentExtractionService.class)
                    .process(created, List.of(pageJpeg())).block();

            assertThat(done.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
            assertThat(scalar("select count(*) from baymax.observation where document_id='" + created.id() + "'"))
                    .isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.stored_object where document_id='" + created.id()
                    + "' and kind='crop'")).isEqualTo("0");
        });
    }

    private static byte[] pageJpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private static void seedFamily(UUID family, UUID patient) throws Exception {
        sql("insert into baymax.family_account (id, whatsapp_number, owner_name, terms_accepted_at) values ('"
                + family + "','+880" + System.nanoTime() % 10000000000L + "','o', now())");
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
