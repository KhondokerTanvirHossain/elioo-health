package com.elioo.baymax.storage.adapter.out.s3;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real S3 API (MinIO): round trip, signed URL expiry, delete-by-prefix, and the blank-prefix guard. */
@Testcontainers
class S3StorageAdapterTest {

    static final String BUCKET = "baymax-test";

    // MinIO left Docker Hub; images live on quay.io. Pinned so the test is reproducible.
    static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

    static S3StorageAdapter adapter;

    @BeforeAll
    static void createBucketAndAdapter() {
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
        adapter = S3StorageAdapter.create(storageSettings());
    }

    @AfterAll
    static void closeAdapter() {
        adapter.close();
    }

    static BaymaxProperties.Storage storageSettings() {
        BaymaxProperties.Storage cfg = new BaymaxProperties.Storage();
        cfg.setEndpoint(MINIO.getS3URL());
        cfg.setRegion("us-east-1");
        cfg.setBucket(BUCKET);
        cfg.setAccessKey(MINIO.getUserName());
        cfg.setSecretKey(MINIO.getPassword());
        cfg.setPathStyle(true);
        cfg.setServerSideEncryption(""); // MinIO without a KMS rejects SSE-S3; AWS gets AES256 from the default
        return cfg;
    }

    @Test
    void putThenGetRoundTripsAndListsTheKey() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StepVerifier.create(adapter.put("rt/a/page-1.jpg", bytes, "image/jpeg")).expectNext(5L).verifyComplete();
        StepVerifier.create(adapter.get("rt/a/page-1.jpg")).expectNextMatches(b -> new String(b, StandardCharsets.UTF_8).equals("hello")).verifyComplete();
        StepVerifier.create(adapter.listKeys("rt/")).expectNext("rt/a/page-1.jpg").verifyComplete();
    }

    @Test
    void signedUrlServesTheObjectAndStopsWorkingAfterTtl() throws Exception {
        adapter.put("sig/doc/page-1.jpg", "proof".getBytes(StandardCharsets.UTF_8), "image/jpeg").block();
        URI url = adapter.signedGetUrl("sig/doc/page-1.jpg", Duration.ofSeconds(1)).block();
        assertThat(url).isNotNull();
        assertThat(url.getQuery()).contains("X-Amz-Signature");

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> fresh = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(fresh.body()).isEqualTo("proof");

        Thread.sleep(2500);
        HttpResponse<String> expired = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(expired.statusCode()).isEqualTo(403);

        // and the object is not reachable without a signature at all
        URI bare = URI.create(url.getScheme() + "://" + url.getAuthority() + url.getPath());
        assertThat(http.send(HttpRequest.newBuilder(bare).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void deleteByPrefixRemovesOnlyThatPrefix() {
        byte[] b = {1};
        adapter.put("del/f1/p1/d1/page-1.jpg", b, "image/jpeg").block();
        adapter.put("del/f1/p1/d1/crop-x.jpg", b, "image/jpeg").block();
        adapter.put("del/f1/p1/d2/page-1.jpg", b, "image/jpeg").block();
        adapter.put("del/f1/p2/d3/page-1.jpg", b, "image/jpeg").block();

        StepVerifier.create(adapter.deleteByPrefix("del/f1/p1/d1/")).expectNext(2L).verifyComplete();
        StepVerifier.create(adapter.listKeys("del/")).expectNext("del/f1/p1/d2/page-1.jpg", "del/f1/p2/d3/page-1.jpg").verifyComplete();

        StepVerifier.create(adapter.deleteByPrefix("del/f1/")).expectNext(2L).verifyComplete();
        StepVerifier.create(adapter.listKeys("del/")).verifyComplete();
        StepVerifier.create(adapter.deleteByPrefix("del/nothing/")).expectNext(0L).verifyComplete();
    }

    @Test
    void blankPrefixIsRefused() {
        StepVerifier.create(adapter.deleteByPrefix("")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(adapter.deleteByPrefix(null)).expectError(IllegalArgumentException.class).verify();
    }
}
