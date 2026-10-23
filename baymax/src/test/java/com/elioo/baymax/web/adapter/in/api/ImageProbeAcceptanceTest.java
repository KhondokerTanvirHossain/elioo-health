package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.testsupport.LoggedText;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.elioo.baymax.web.adapter.in.ui.BaymaxWebUiRouter;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String NODE = System.getenv().getOrDefault("NODE_BINARY", "node");

    private static ReactiveWebApplicationContextRunner runner(String secretKey) {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, S3, "minioadmin", secretKey,
                        new ExtractionPipelineAcceptanceTest.StubReply(PaintedPaper.NOW_REPLY, List.of("Potassium", "11.0")))
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false",
                        "baymax.auth.hmac-secret=probe-secret", "baymax.auth.cookie-secure=false",
                        "baymax.web.public-origin=" + AppApiContext.ORIGIN);
    }

    private static ReactiveWebApplicationContextRunner runner() {
        return runner("minioadmin");
    }

    @Test
    void aRealBrowserLoadsEveryImageOnAReadPaper() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Seeded paper = seed(context, "+8801700000741");
            DisposableServer server = serve(context);
            try {
                JsonNode report = probe(server, paper, "image-probe.json");
                everyPageIsWhole(report);
                // before the decode check, so a CSP that blocks the 302's target fails naming img-src and the blocked URL
                assertThat(violations(report)).as("CSP violations").isEmpty();
                everyPageWentQuiet(report);
                List<JsonNode> crops = ofClass(imgs(report), "crop");
                List<JsonNode> pages = ofClass(imgs(report), "page-img");
                assertThat(imgs(report)).as("every img decoded").allSatisfy(i ->
                        assertThat(i.path("naturalWidth").asInt()).as("naturalWidth of " + i.path("src").asText() + " " + i.path("final")).isPositive());
                // H7: while images 302 to S3 they are cross-origin, so no canvas can read a crop's pixels. D4a Step 4's
                // same-origin route turns this red on purpose: flip it to tainted == false and assert the crop's samples
                // are all PaintedPaper.WORDS, which is DR-12's content check made in the browser.
                assertThat(imgs(report)).as("H7: every decoded img is tainted for the canvas while it goes through the 302").allSatisfy(i ->
                        assertThat(i.path("tainted").asBoolean()).as("tainted of " + i.path("src").asText()).isTrue());
                int pageWidth = pages.get(0).path("naturalWidth").asInt();
                assertThat(crops).as("every value crop is narrower than the page").allSatisfy(c ->
                        assertThat(c.path("naturalWidth").asInt()).isLessThan(pageWidth));
                aSignedUrlWasThereToLeak(report);
            } finally {
                server.disposeNow();
            }
        });
    }

    /**
     * Step 3's check of what the app's own log can say about a missing image: a crop object deleted from the bucket (H1),
     * and an image route refusing a real key from the family's other paper (H2's shape, {@code image_not_found}).
     *
     * <p><b>This pins a current observability gap, not a wanted behaviour.</b> Neither leaves a line at INFO or above: the
     * first fails in the browser after the 302, where the app never sees it; the second is a {@code BaymaxException} whose
     * reason no error path logs. So a count of {@code image_not_found} in the container log is no evidence for or against
     * either. D4a's proposed INFO access line for a non-200 image answer turns this test red on purpose; when it lands,
     * invert the last assertion into "the refusal is logged, by route pattern, without the key".
     *
     * <p>Two controls keep the absence honest: the API access line and a canary through each error path's own logger
     * must reach the appender in the same window, and the paper's own key through the same route must be signed (302),
     * so the 404 is the prefix check and not a route or a document miss.
     */
    @Test
    void aMissingImageLeavesNoLineAboutItAtInfoOrAbove() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Seeded paper = seed(context, "+8801700000742");
            Seeded other = upload(context, paper.patient(), paper.token());
            PatientProfile ma = paper.patient();
            StoragePort bucket = context.getBean(StoragePort.class);
            String cropKey = StorageKeys.crop(ma.familyId(), ma.id(), paper.document(), "v1");
            String othersKey = StorageKeys.crop(ma.familyId(), ma.id(), other.document(), "v1");
            assertThat(bucket.listKeys(othersKey).collectList().block()).as("the other paper's crop is real").containsExactly(othersKey);
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
                WebTestClient web = WebTestClient.bindToApplicationContext(context).build();
                web.get().uri(b -> b.path("/app/documents/{id}/image").queryParam("key", "{k}").build(paper.document(), othersKey))
                        .cookie("baymax_session", paper.token()).exchange().expectStatus().isNotFound();
                // the same route, the same paper, its own (deleted) key: signed, so the 404 above is the prefix check
                web.get().uri(b -> b.path("/app/documents/{id}/image").queryParam("key", "{k}").build(paper.document(), cropKey))
                        .cookie("baymax_session", paper.token()).exchange().expectStatus().isFound();
                // positive controls: a real INFO line, and one through each error path's logger
                web.get().uri("/app/api/me").cookie("baymax_session", paper.token()).exchange().expectStatus().isOk();
                LoggerFactory.getLogger(BaymaxWebUiRouter.class).info("[probe-canary] html error path");
                LoggerFactory.getLogger(ErrorResponseFilter.class).info("[probe-canary] json error path");
            } finally {
                server.disposeNow();
                root.detachAppender(appender);
                root.setLevel(before);
            }
            everyPageIsWhole(report);
            List<JsonNode> crops = ofClass(imgs(report), "crop");
            assertThat(crops).as("the deleted crop does not decode").allSatisfy(c -> assertThat(c.path("naturalWidth").asInt()).isZero());
            aSignedUrlWasThereToLeak(report);

            List<ILoggingEvent> atInfo = LogCapture.events(appender).stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
            assertThat(LoggedText.atInfoOrAbove(atInfo)).as("positive control: the appender saw the window at INFO")
                    .anySatisfy(t -> assertThat(t).startsWith("[baymax-api] GET /app/api/me 200"))
                    .anySatisfy(t -> assertThat(t).isEqualTo("[probe-canary] html error path"))
                    .anySatisfy(t -> assertThat(t).isEqualTo("[probe-canary] json error path"));
            List<String> markers = List.of("image_not_found", "no such image", "no such page", "BaymaxException", "/image",
                    paper.document().toString(), cropKey, othersKey);
            assertThat(atInfo.stream().filter(e -> isBaymaxException(e.getThrowableProxy())
                            || markers.stream().anyMatch(LoggedText.of(e)::contains)).map(LoggedText::of))
                    .as("lines at INFO or above about the missing crop or the refused key").isEmpty();
        });
    }

    /**
     * Step 3's gate, the other way to reproduce: the bucket shaped as production's is (the startup line on the box reads
     * {@code pathStyle=false sse=AES256 credentials=INSTANCE_ROLE}) — virtual-host style, so the 302 goes to
     * {@code http://baymax.localhost:<port>}, a host of its own, and every object written with
     * {@code x-amz-server-side-encryption: AES256} (MinIO's built-in KMS key). Both are asserted, not assumed: the 302's
     * target host, and the stored objects' encryption read back by HEAD. What this cannot reproduce here: TLS, and the
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
                            everyPageIsWhole(report);
                            String virtualHost = "http://baymax.localhost:" + s3.getMappedPort(9000);
                            assertThat(imgs(report)).as("every img 302s to the bucket's own host, then answers").allSatisfy(i -> {
                                JsonNode hops = i.path("hops");
                                assertThat(hops.size()).as("hops of " + i.path("src").asText() + " " + hops).isEqualTo(2);
                                assertThat(hops.get(0).path("status").asInt()).as("first hop of " + i.path("src").asText()).isEqualTo(302);
                                assertThat(hops.get(0).path("locationOrigin").asText()).as("the 302's target of " + i.path("src").asText())
                                        .isEqualTo(virtualHost);
                            });
                            assertThat(violations(report)).as("CSP violations").isEmpty();
                            everyPageWentQuiet(report);
                            assertThat(imgs(report)).as("every img decoded").allSatisfy(i -> assertThat(i.path("naturalWidth").asInt())
                                    .as("naturalWidth of " + i.path("src").asText() + " " + i.path("final")).isPositive());
                            PatientProfile ma = paper.patient();
                            try (S3Client head = S3Client.builder().endpointOverride(URI.create(s3.getS3URL())).region(Region.US_EAST_1)
                                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.getUserName(), s3.getPassword())))
                                    .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                                for (String key : List.of(StorageKeys.page(ma.familyId(), ma.id(), paper.document(), 1),
                                        StorageKeys.crop(ma.familyId(), ma.id(), paper.document(), "v1"))) {
                                    assertThat(head.headObject(r -> r.bucket(ExtractionPipelineAcceptanceTest.BUCKET).key(key)).serverSideEncryption())
                                            .as("the stored object's encryption (" + key.substring(key.lastIndexOf('/') + 1) + ")")
                                            .isEqualTo(ServerSideEncryption.AES256);
                                }
                            }
                            aSignedUrlWasThereToLeak(report);
                        } finally {
                            server.disposeNow();
                        }
                    });
        }
    }

    /**
     * H3 in the failure direction, so its "ruled out" rests on a probe shown able to see it: the paper is read by a context
     * with the right key, then served by one whose presigner signs with a wrong secret, so S3 answers
     * {@code 403 SignatureDoesNotMatch}. In the page that is exactly what a deleted object (404) is: the 302 hop, then no
     * response the page can see, {@code naturalWidth} 0 and {@code net::ERR_BLOCKED_BY_ORB}. Expired tokens were not run.
     */
    @Test
    void aWrongSignatureShowsInThePageOnlyAsBlockedByOrb() {
        runner().run(reader -> {
            assertThat(reader).hasNotFailed();
            Seeded paper = seed(reader, "+8801700000744");
            runner("not-the-secret").run(context -> {
                assertThat(context).hasNotFailed();
                DisposableServer server = serve(context);
                try {
                    JsonNode report = probe(server, paper, "image-probe-bad-signature.json");
                    everyPageIsWhole(report);
                    assertThat(imgs(report)).as("every img 302s to S3, and nothing comes back that the page can see").allSatisfy(i -> {
                        JsonNode hops = i.path("hops");
                        assertThat(hops.get(0).path("status").asInt()).as("first hop of " + i.path("src").asText()).isEqualTo(302);
                        assertThat(hops.get(0).path("locationOrigin").asText()).isEqualTo(S3);
                        assertThat(i.path("naturalWidth").asInt()).as("naturalWidth of " + i.path("src").asText()).isZero();
                    });
                    List<JsonNode> failed = new ArrayList<>();
                    report.path("pages").forEach(p -> p.path("failedRequests").forEach(failed::add));
                    assertThat(failed).as("the failed requests the page saw").isNotEmpty().allSatisfy(f -> {
                        assertThat(f.path("url").asText()).startsWith(S3 + "/");
                        assertThat(f.path("error").asText()).isEqualTo("net::ERR_BLOCKED_BY_ORB");
                    });
                    aSignedUrlWasThereToLeak(report);
                } finally {
                    server.disposeNow();
                }
            });
        });
    }

    /**
     * The probe runs only against this machine and writes only where git ignores, and takes the session from the
     * environment: it can never be aimed at production with a real family's cookie (that is P4, a manual Chrome step),
     * nor leave a report holding key paths in a tracked file.
     */
    @Test
    void theProbeRefusesAnyHostButThisMachineAndAnyPathGitWouldTrack() throws Exception {
        String doc = UUID.randomUUID().toString();
        Map<String, String> session = Map.of("PROBE_SESSION", "tok-not-a-real-session");
        ProbeRun production = runProbe(session, "refused-production", "--base", "https://medioo.eliooo.org", "--doc", doc,
                "--out", OUT.resolve("refused.json").toString());
        assertThat(production.exit()).as(production.output()).isNotZero();
        assertThat(production.output()).contains("refusing --base https://medioo.eliooo.org").contains("P4")
                .doesNotContain("tok-not-a-real-session");

        Path tracked = REPO.resolve("medioo-web/build/d4/refused.json");
        ProbeRun untracked = runProbe(session, "refused-tracked", "--base", "http://localhost:1", "--doc", doc, "--out", tracked.toString());
        assertThat(untracked.exit()).as(untracked.output()).isNotZero();
        assertThat(untracked.output()).contains("refusing --out").contains("git does not ignore");
        assertThat(tracked).doesNotExist();

        ProbeRun noSession = runProbe(Map.of(), "refused-nosession", "--base", "http://localhost:1", "--doc", doc,
                "--out", OUT.resolve("refused.json").toString());
        assertThat(noSession.exit()).as(noSession.output()).isNotZero();
        assertThat(noSession.output()).contains("PROBE_SESSION");
    }

    record Seeded(PatientProfile patient, UUID document, String token) {
    }

    /** A family, a patient, a session, and the painted page uploaded through the one entry and read to DONE. */
    static Seeded seed(ApplicationContext context, String number) throws Exception {
        FamilyAccount family = AppApiContext.family(context, number, "Rahim");
        PatientProfile ma = AppApiContext.patient(context, family.id(), "Rokeya", 68, PatientProfile.Sex.FEMALE);
        return upload(context, ma, AppApiContext.session(context, family.id()));
    }

    /** One more painted page for this patient, uploaded through {@code POST /app/add} and read to DONE. */
    static Seeded upload(ApplicationContext context, PatientProfile ma, String token) throws Exception {
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

    record ProbeRun(int exit, String output) {
    }

    /** The probe as a process, with only {@code env} added to a copy of this JVM's environment (never PROBE_SESSION else). */
    static ProbeRun runProbe(Map<String, String> env, String name, String... args) throws Exception {
        Files.createDirectories(OUT);
        List<String> command = new ArrayList<>(List.of(NODE, PROBE.toString()));
        command.addAll(List.of(args));
        Path log = OUT.resolve(name + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("PROBE_SESSION");
        builder.environment().putAll(env);
        Process p = builder.start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
        }
        assertThat(done).as("the probe finished within 120 s (" + log + ")").isTrue();
        return new ProbeRun(p.exitValue(), Files.readString(log));
    }

    /**
     * Runs the probe on the paper and its {@code /all}, the session passed in the environment, never on argv. The report
     * and the probe's own output must hold no signed-URL credential; and since that is vacuous without a signed URL to
     * leak, at least one 302 to storage must have been recorded.
     */
    static JsonNode probe(DisposableServer server, Seeded paper, String file) throws Exception {
        Path out = OUT.resolve(file);
        Files.deleteIfExists(out);
        ProbeRun run = runProbe(Map.of("PROBE_SESSION", paper.token()), file, "--base", "http://localhost:" + server.port(),
                "--doc", paper.document().toString(), "--out", out.toString());
        assertThat(run.exit()).as("the probe's exit code; its output: " + run.output()).isZero();
        String raw = Files.readString(out);
        for (String credential : List.of("X-Amz-", "Signature=", "Credential=")) {
            assertThat(raw).as("the report holds no signed-URL credential").doesNotContain(credential);
            assertThat(run.output()).as("the probe's output holds no signed-URL credential").doesNotContain(credential);
        }
        return JSON.readTree(new File(out.toString()));
    }

    /**
     * The credential scan in {@link #probe} is vacuous unless a signed URL was there to leak: at least one 302 to storage
     * must have been recorded. Each browser test calls this after its own assertions, so a probe whose redirects were
     * blocked (a CSP replay) fails on the blocking first, not here.
     */
    static void aSignedUrlWasThereToLeak(JsonNode report) {
        assertThat(imgs(report).stream().flatMap(i -> {
            List<JsonNode> hops = new ArrayList<>();
            i.path("hops").forEach(hops::add);
            return hops.stream();
        }).filter(h -> h.path("status").asInt() == 302 && h.hasNonNull("locationOrigin")))
                .as("302s to storage recorded: there was a signed URL the report could have leaked").isNotEmpty();
    }

    /** Each probed page answered 200 and shows at least one value crop and one page image: no page can pass on another's. */
    static void everyPageIsWhole(JsonNode report) {
        assertThat(report.path("pages").size()).as("pages probed").isEqualTo(2);
        report.path("pages").forEach(page -> {
            String path = page.path("path").asText();
            assertThat(page.path("status").asInt()).as("status of " + path).isEqualTo(200);
            List<JsonNode> imgs = new ArrayList<>();
            page.path("imgs").forEach(imgs::add);
            assertThat(ofClass(imgs, "crop")).as("value crops on " + path + " (not vacuous)").isNotEmpty();
            assertThat(ofClass(imgs, "page-img")).as("page images on " + path + " (not vacuous)").isNotEmpty();
        });
    }

    /** On the healthy path every page's network went quiet: a hung page is a finding, and visible. */
    static void everyPageWentQuiet(JsonNode report) {
        report.path("pages").forEach(page ->
                assertThat(page.path("networkIdle").asBoolean()).as("networkIdle of " + page.path("path").asText()).isTrue());
    }

    static List<JsonNode> ofClass(List<JsonNode> imgs, String cls) {
        return imgs.stream().filter(i -> List.of(i.path("cls").asText().split("\\s+")).contains(cls)).toList();
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

    private static boolean isBaymaxException(IThrowableProxy t) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t.getClassName().endsWith(".BaymaxException")) {
                return true;
            }
        }
        return false;
    }
}
