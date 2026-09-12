package com.elioo.baymax.storage.application.service;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BMX-3 acceptance through the real wiring: MinIO for the bucket, Postgres for the ledger, the Baymax
 * auto-configuration for everything in between.
 */
@Testcontainers
class DocumentStorageAcceptanceTest {

    static final String BUCKET = "baymax";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    // MinIO left Docker Hub; images live on quay.io. Pinned so the test is reproducible.
    static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z";

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
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true",
                        "llm.provider=groq", "llm.groq.api-key=test-key",
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
                        "baymax.storage.server-side-encryption=",
                        "baymax.storage.signed-url-ttl=PT15M");
    }

    @Test
    void uploadKeepsOnlyKeysInPostgresAndDeleteRemovesEverything() {
        UUID family = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID otherDocument = UUID.randomUUID();

        runner().run(context -> {
            DocumentStorageUseCase storage = context.getBean(DocumentStorageUseCase.class);
            StoragePort bucket = context.getBean(StoragePort.class);

            StoredObject page1 = storage.storePage(family, patient, document, 1, JPEG).block();
            storage.storePage(family, patient, document, 2, JPEG).block();
            storage.storeCrop(family, patient, document, "hba1c", JPEG).block();
            storage.storePage(family, patient, otherDocument, 1, JPEG).block();

            // the DB row holds the key and size, never bytes: no bytea column exists, and the row is tiny
            assertThat(columnTypes("stored_object")).doesNotContain("bytea");
            assertThat(sql("select storage_key from baymax.stored_object where id = '" + page1.id() + "'"))
                    .isEqualTo(family + "/" + patient + "/" + document + "/page-1.jpg");
            assertThat(sql("select size_bytes from baymax.stored_object where id = '" + page1.id() + "'"))
                    .isEqualTo(String.valueOf(JPEG.length));
            assertThat(storage.bytesStored(document).block()).isEqualTo(3L * JPEG.length);

            List<StoredObject> objects = storage.objectsOf(document).collectList().block();
            assertThat(objects).extracting(StoredObject::storageKey).containsExactlyInAnyOrder(
                    family + "/" + patient + "/" + document + "/page-1.jpg",
                    family + "/" + patient + "/" + document + "/page-2.jpg",
                    family + "/" + patient + "/" + document + "/crop-hba1c.jpg");

            // the family sees the crop through a signed URL only
            URI url = storage.signedUrl(objects.get(0).storageKey()).block();
            HttpResponse<byte[]> got = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(got.statusCode()).isEqualTo(200);
            assertThat(got.body()).isEqualTo(JPEG);

            // delete-on-request for one document leaves the other document alone
            DeletionReport report = storage.deleteDocument(family, patient, document).block();
            assertThat(report.objectsDeleted()).isEqualTo(3);
            assertThat(report.ledgerRowsDeleted()).isEqualTo(3);
            assertThat(bucket.listKeys(family + "/" + patient + "/" + document + "/").collectList().block()).isEmpty();
            assertThat(storage.objectsOf(document).collectList().block()).isEmpty();
            assertThat(storage.bytesStored(document).block()).isZero();
            assertThat(bucket.listKeys(family + "/").collectList().block())
                    .containsExactly(family + "/" + patient + "/" + otherDocument + "/page-1.jpg");
            assertThat(sql("select count(*) from baymax.stored_object")).isEqualTo("1");

            // whole-family delete (the BMX-4 path) clears the rest
            DeletionReport family_report = storage.deleteFamily(family).block();
            assertThat(family_report.objectsDeleted()).isEqualTo(1);
            assertThat(bucket.listKeys(family + "/").collectList().block()).isEmpty();
            assertThat(sql("select count(*) from baymax.stored_object")).isEqualTo("0");
        });
    }

    private static String columnTypes(String table) throws Exception {
        return sql("select string_agg(data_type, ',') from information_schema.columns where table_schema='baymax' and table_name='" + table + "'");
    }

    private static String sql(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
