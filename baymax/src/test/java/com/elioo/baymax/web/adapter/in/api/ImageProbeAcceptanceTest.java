package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.testsupport.LoggedText;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 (wave 2 plan, Task D4a): what a real browser does with every image on a read paper, before anything about the
 * images is changed. The whole Baymax context runs on a real port over Testcontainers Postgres and the
 * <b>docker-compose MinIO</b> ({@code http://localhost:9000}, which Chromium outside the JVM can reach, as it reaches S3
 * in production); OCR and the model are stubbed with {@link PaintedPaper#NOW_REPLY}. A family uploads the painted page
 * through {@code POST /app/add}, and {@code medioo-web/e2e/d4/image-probe.mjs} opens the paper and its {@code /all} in
 * Chromium and records each image's hops, decode and pixels.
 *
 * <p>Gated by {@code RUN_BROWSER_PROBE=true} (it needs {@code docker compose up -d}, Node and Playwright's Chromium), like
 * the AWS and LLM gates in CLAUDE.md: {@code RUN_BROWSER_PROBE=true ./gradlew :baymax:test --tests '*ImageProbeAcceptanceTest'}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_BROWSER_PROBE", matches = "true")
class ImageProbeAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** The docker-compose MinIO; Bitnami creates the {@code baymax} bucket itself (docker-compose.yml). */
    static final String S3 = "http://localhost:9000";
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path PROBE = REPO.resolve("medioo-web/e2e/d4/image-probe.mjs");
    static final Path OUT = Path.of("build/d4").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ReactiveWebApplicationContextRunner runner() {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, S3, "minioadmin", "minioadmin",
                        new ExtractionPipelineAcceptanceTest.StubReply(PaintedPaper.NOW_REPLY, List.of("Potassium", "11.0")))
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false",
                        "baymax.auth.hmac-secret=probe-secret", "baymax.auth.cookie-secure=false",
                        "baymax.web.public-origin=" + AppApiContext.ORIGIN);
    }

    @Test
    void aRealBrowserLoadsEveryImageOnAReadPaper() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Seeded paper = seed(context, "+8801700000741");
            DisposableServer server = serve(context);
            try {
                JsonNode report = probe(server, paper, "image-probe.json");
                List<JsonNode> imgs = imgs(report);
                List<JsonNode> crops = imgs.stream().filter(i -> i.path("cls").asText().contains("crop")).toList();
                List<JsonNode> pages = imgs.stream().filter(i -> i.path("cls").asText().contains("page-img")).toList();
                assertThat(crops).as("value crops the probe found (not vacuous)").isNotEmpty();
                assertThat(pages).as("page images the probe found (not vacuous)").isNotEmpty();
                assertThat(imgs).as("every img decoded").allSatisfy(i ->
                        assertThat(i.path("naturalWidth").asInt()).as("naturalWidth of " + i.path("src").asText() + " " + i.path("final")).isPositive());
                assertThat(violations(report)).as("CSP violations").isEmpty();
                int pageWidth = pages.get(0).path("naturalWidth").asInt();
                assertThat(crops).as("every value crop is narrower than the page").allSatisfy(c ->
                        assertThat(c.path("naturalWidth").asInt()).isLessThan(pageWidth));
            } finally {
                server.disposeNow();
            }
        });
    }

    /**
     * Step 3's check of what the app's own log can say: a crop object deleted from the bucket (H1), and an image route
     * refusing a key outside the paper's prefix (H2's shape, {@code image_not_found}). In production neither leaves a line
     * at INFO: the first fails in the browser after the 302, where the app never sees it; the second is a
     * {@code BaymaxException} whose reason no error path logs. So a count of {@code image_not_found} in the container log
     * is no evidence for or against either.
     */
    @Test
    void aMissingImageLeavesNothingInTheAppLog() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Seeded paper = seed(context, "+8801700000742");
            StoragePort bucket = context.getBean(StoragePort.class);
            String cropKey = StorageKeys.crop(paper.patient().familyId(), paper.patient().id(), paper.document(), "v1");
            assertThat(bucket.listKeys(cropKey).collectList().block()).as("the crop is there before it is deleted").containsExactly(cropKey);
            assertThat(bucket.deleteByPrefix(cropKey).block()).isEqualTo(1L);

            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level before = root.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            root.addAppender(appender);
            root.setLevel(Level.INFO);
            DisposableServer server = serve(context);
            JsonNode report;
            try {
                report = probe(server, paper, "image-probe-deleted.json");
                WebTestClient.bindToApplicationContext(context).build().get()
                        .uri(b -> b.path("/app/documents/{id}/image").queryParam("key", "{k}")
                                .build(paper.document(), StorageKeys.crop(UUID.randomUUID(), UUID.randomUUID(), paper.document(), "v1")))
                        .cookie("baymax_session", paper.token()).exchange().expectStatus().isNotFound();
            } finally {
                server.disposeNow();
                root.detachAppender(appender);
                root.setLevel(before);
            }
            List<JsonNode> crops = imgs(report).stream().filter(i -> i.path("cls").asText().contains("crop")).toList();
            assertThat(crops).as("the deleted crop's img on the paper").isNotEmpty();
            assertThat(crops).as("the deleted crop does not decode").allSatisfy(c -> assertThat(c.path("naturalWidth").asInt()).isZero());
            assertThat(appender.list.stream().map(LoggedText::of).filter(t -> t.contains("image_not_found")))
                    .as("app log lines naming image_not_found").isEmpty();
        });
    }

    /**
     * Step 3's gate, the other way to reproduce: the bucket shaped as production's is (the startup line on the box reads
     * {@code region=ap-south-1 endpoint=aws pathStyle=false sse=AES256 credentials=INSTANCE_ROLE}) — virtual-host style,
     * so the 302 goes to {@code http://baymax.localhost:<port>}, a host of its own, and every object written with
     * {@code x-amz-server-side-encryption: AES256} (MinIO's built-in KMS key). What this cannot reproduce here: TLS, and the
     * session token an instance role adds to the signature ({@code X-Amz-Security-Token}).
     */
    @Test
    void aVirtualHostedEncryptedBucketLoadsEveryImageToo() {
        try (MinIOContainer s3 = com.elioo.baymax.storage.TestImages.minioContainer()
                .withEnv("MINIO_DOMAIN", "localhost")
                .withEnv("MINIO_KMS_SECRET_KEY", "probe-key:" + java.util.Base64.getEncoder().encodeToString(new byte[32]))) {
            s3.start();
            ExtractionPipelineAcceptanceTest.createBucket(s3);
            ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, s3.getS3URL(), s3.getUserName(), s3.getPassword(),
                            new ExtractionPipelineAcceptanceTest.StubReply(PaintedPaper.NOW_REPLY, List.of("Potassium", "11.0")))
                    .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                    .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false",
                            "baymax.auth.hmac-secret=probe-secret", "baymax.auth.cookie-secure=false",
                            "baymax.web.public-origin=" + AppApiContext.ORIGIN,
                            "baymax.storage.path-style=false", "baymax.storage.server-side-encryption=AES256")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        Seeded paper = seed(context, "+8801700000743");
                        DisposableServer server = serve(context);
                        try {
                            JsonNode report = probe(server, paper, "image-probe-virtual-host.json");
                            List<JsonNode> imgs = imgs(report);
                            assertThat(imgs).as("images the probe found (not vacuous)").hasSizeGreaterThanOrEqualTo(2);
                            assertThat(imgs).as("every img decoded").allSatisfy(i -> assertThat(i.path("naturalWidth").asInt())
                                    .as("naturalWidth of " + i.path("src").asText() + " " + i.path("final")).isPositive());
                            assertThat(violations(report)).as("CSP violations").isEmpty();
                        } finally {
                            server.disposeNow();
                        }
                    });
        }
    }

    record Seeded(PatientProfile patient, UUID document, String token) {
    }

    /** A family, a patient, a session, and the painted page uploaded through the one entry and read to DONE. */
    static Seeded seed(ApplicationContext context, String number) throws Exception {
        FamilyAccount family = AppApiContext.family(context, number, "Rahim");
        PatientProfile ma = AppApiContext.patient(context, family.id(), "Rokeya", 68, PatientProfile.Sex.FEMALE);
        String token = AppApiContext.session(context, family.id());
        WebTestClient web = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();
        String sheet = web.get().uri("/app/add?patient=" + ma.id()).cookie("baymax_session", token).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        Matcher key = Pattern.compile("name=\"upload_key\" value=\"([0-9a-f-]{36})\"").matcher(sheet);
        assertThat(key.find()).as("the sheet carries its press's key").isTrue();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        body.part("upload_key", key.group(1));
        body.part("file", PaintedPaper.paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        String path = web.post().uri("/app/add").cookie("baymax_session", token).body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(path).matches("/app/documents/[0-9a-f-]{36}");
        String status = "";
        for (int i = 0; i < 60 && !status.equals("DONE"); i++) {
            Thread.sleep(500);
            status = web.get().uri(path + "/status").cookie("baymax_session", token).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody().replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
        }
        assertThat(status).as("the painted page was read").isEqualTo("DONE");
        return new Seeded(ma, UUID.fromString(path.substring(path.lastIndexOf('/') + 1)), token);
    }

    /** The context's own WebHandler on a real port: what Caddy forwards to in production. */
    static DisposableServer serve(ApplicationContext context) {
        return HttpServer.create().host("localhost").port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build()))
                .bindNow();
    }

    static JsonNode probe(DisposableServer server, Seeded paper, String file, String... extraPaths) throws Exception {
        Path out = OUT.resolve(file);
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);
        List<String> command = new ArrayList<>(List.of(System.getenv().getOrDefault("NODE_BINARY", "node"), PROBE.toString(),
                "--base", "http://localhost:" + server.port(), "--cookie", paper.token(), "--doc", paper.document().toString(),
                "--out", out.toString()));
        if (extraPaths.length > 0) {
            command.addAll(List.of("--paths", String.join(",", extraPaths)));
        }
        Path log = OUT.resolve(file + ".log");
        Process p = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
        }
        assertThat(done).as("the probe finished within 120 s (" + log + ")").isTrue();
        assertThat(p.exitValue()).as("the probe's exit code; its output: " + Files.readString(log)).isZero();
        return JSON.readTree(new File(out.toString()));
    }

    static List<JsonNode> imgs(JsonNode report) {
        List<JsonNode> all = new ArrayList<>();
        report.path("pages").forEach(page -> page.path("imgs").forEach(all::add));
        return all;
    }

    static List<JsonNode> violations(JsonNode report) {
        List<JsonNode> all = new ArrayList<>();
        report.path("pages").forEach(page -> page.path("cspViolations").forEach(all::add));
        return all;
    }
}
