package com.elioo.baymax.family;

import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BMX-4 acceptance over the real wiring and HTTP: onboard a family and a patient, store images, then
 * DELETE the family and prove nothing is left in any baymax table or under the storage prefix, with a
 * receipt that matches what existed. Also runs the admin storage self-test against MinIO.
 */
@Testcontainers
class FamilyDeletionAcceptanceTest {

    static final String BUCKET = "baymax";
    static final String TOKEN = "admin-test-token";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

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
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.admin.token=" + TOKEN,
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
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
    @SuppressWarnings("unchecked")
    void deleteFamilyLeavesNothingBehindAndTheReceiptMatches() {
        runner().run(context -> {
            WebTestClient api = WebTestClient.bindToRouterFunction(
                            ((RouterFunction<ServerResponse>) context.getBean("baymaxFamilyRoutes"))
                                    .and((RouterFunction<ServerResponse>) context.getBean("baymaxAdminRoutes")))
                    .build();
            DocumentStorageUseCase storage = context.getBean(DocumentStorageUseCase.class);
            StoragePort bucket = context.getBean(StoragePort.class);

            // onboarding
            String familyId = api.post().uri("/api/v1/baymax/families").header(AdminAuthFilter.HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"whatsapp_number\":\"+8801700000002\",\"owner_name\":\"Rahim\",\"terms_accepted\":true}")
                    .exchange().expectStatus().isCreated()
                    .returnResult(java.util.Map.class).getResponseBody().blockFirst().get("id").toString();
            String patientId = api.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"name\":\"Ma\",\"age\":74,\"sex\":\"female\",\"chronic_flags\":[\"diabetes\"],\"proxy_consent\":true}")
                    .exchange().expectStatus().isCreated()
                    .returnResult(java.util.Map.class).getResponseBody().blockFirst().get("id").toString();
            // free tier: second patient is 402
            api.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"name\":\"Baba\",\"age\":80,\"sex\":\"male\",\"proxy_consent\":true}")
                    .exchange().expectStatus().isEqualTo(402)
                    .expectBody().jsonPath("$.reason").isEqualTo("free_tier_patients");
            // sharing: once ok, twice 409
            api.post().uri("/api/v1/baymax/patients/" + patientId + "/share").header(AdminAuthFilter.HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801811111111\"}")
                    .exchange().expectStatus().isCreated();
            api.post().uri("/api/v1/baymax/patients/" + patientId + "/share").header(AdminAuthFilter.HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801822222222\"}")
                    .exchange().expectStatus().isEqualTo(409);

            // two documents with images, plus an unrelated family's image that must survive
            UUID f = UUID.fromString(familyId);
            UUID p = UUID.fromString(patientId);
            UUID docA = UUID.randomUUID();
            UUID docB = UUID.randomUUID();
            storage.storePage(f, p, docA, 1, JPEG).block();
            storage.storePage(f, p, docA, 2, JPEG).block();
            storage.storeCrop(f, p, docA, "hba1c", JPEG).block();
            storage.storePage(f, p, docB, 1, JPEG).block();
            UUID otherFamily = UUID.randomUUID();
            storage.storePage(otherFamily, UUID.randomUUID(), UUID.randomUUID(), 1, JPEG).block();
            sql("insert into baymax.ai_call_log (document_id, purpose, provider, model, input_tokens) values ('" + docA + "','extract','groq','m',10)");

            // admin storage self-test against the same bucket
            api.get().uri("/api/v1/baymax/admin/storage/selftest").header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.ok").isEqualTo(true)
                    .jsonPath("$.steps.put").value(v -> assertThat(v.toString()).startsWith("ok"))
                    .jsonPath("$.steps.presign").isEqualTo("ok")
                    .jsonPath("$.steps.get").isEqualTo("ok")
                    .jsonPath("$.steps.delete").isEqualTo("ok");
            assertThat(bucket.listKeys("_selftest/").collectList().block()).isEmpty();

            // delete-on-request
            api.delete().uri("/api/v1/baymax/families/" + familyId).header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.families").isEqualTo(1)
                    .jsonPath("$.patients").isEqualTo(1)
                    .jsonPath("$.documents").isEqualTo(2)
                    .jsonPath("$.objects").isEqualTo(4)
                    .jsonPath("$.deleted_at").isNotEmpty();

            assertThat(scalar("select count(*) from baymax.family_account where id = '" + familyId + "'")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.patient_profile where family_id = '" + familyId + "'")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.share_member where patient_id = '" + patientId + "'")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.stored_object where family_id = '" + familyId + "'")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.ai_call_log where document_id = '" + docA + "'")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.ai_call_log where document_id is null and input_tokens = 10")).isEqualTo("1");
            assertThat(bucket.listKeys(familyId + "/").collectList().block()).isEmpty();
            assertThat(bucket.listKeys(otherFamily + "/").collectList().block()).hasSize(1);

            api.delete().uri("/api/v1/baymax/families/" + familyId).header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange().expectStatus().isNotFound();
        });
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
