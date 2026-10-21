package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan B4b step 8: the 60 MB press cap through a real server, not a mock exchange. A {@code WebTestClient} bound to a
 * router never streams a body over a socket and never sends one chunked, so it cannot show that a 59 MB press arrives
 * whole or that a 61 MB one with no Content-Length is still refused. Here reactor-netty serves the real guard and the
 * real {@link UploadApiHandler}; the upload use case is a stub that records each file's byte count and SHA-256, so
 * "arrived whole" is checked on the content, never on its presence. The client runs on its own event loops, as a
 * browser would, so it never shares a thread with the server it is testing.
 */
class UploadApiRealServerTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4220001");
    private static final UUID MA = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4220002");
    private static final String KEY = "7d1e0c2b-4a5f-4b6e-8c9d-0e1f2a3b4c50";
    private static final String BOUNDARY = "----mediooRealServerBoundary9xQ2";
    private static final int MB = 1024 * 1024;

    /** What reached the use case: one entry per file, its byte count and its SHA-256. */
    record Received(int bytes, String sha256) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    /** Bytes of request body the application read, over every request of a test. */
    private static final AtomicLong SERVER_READ = new AtomicLong();
    private static DisposableServer server;
    private static WebClient client;
    private static LoopResources clientLoops;

    @BeforeAll
    static void start() {
        WebAuthUseCase auth = mock(WebAuthUseCase.class);
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        BaymaxProperties properties = new BaymaxProperties();
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(new SessionAuthFilter(auth, properties), properties);
        var router = new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(mock(TimelineUseCase.class), mock(AttentionUseCase.class), properties),
                new SignInApiHandler(auth, new SessionAuthFilter(auth, properties), properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy()),
                new UploadApiHandler(new RecordingUploads(), properties, new com.elioo.baymax.web.adapter.in.ui.UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(mock(TimelineUseCase.class), new DeleteConfirmation(properties, java.time.Clock.systemUTC())),
                guard);
        HttpHandler app = RouterFunctions.toHttpHandler(router);
        // what the application pulled off the socket, counted below the exchange: the server's own side of the cap
        HttpHandler counted = (request, response) -> app.handle(new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().doOnNext(b -> SERVER_READ.addAndGet(b.readableByteCount()));
            }
        }, response);
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(counted))
                .bindNow();
        clientLoops = LoopResources.create("upload-client", 2, true);
        client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().runOn(clientLoops)))
                .build();
    }

    @AfterAll
    static void stop() {
        server.disposeNow();
        clientLoops.disposeLater().block(Duration.ofSeconds(10));
    }

    private Set<Path> spooledBefore;

    @BeforeEach
    void forget() throws IOException {
        RECEIVED.clear();
        SERVER_READ.set(0);
        spooledBefore = spooled();
    }

    /**
     * Every part the reader spooled to disk is gone once the press is answered: accepted, refused at the cap, or refused
     * by its part count. Checked on the spool directory itself, not on a delete call: a photo of a report left in
     * {@code java.io.tmpdir} is a patient's paper on the shared disk.
     */
    @AfterEach
    void nothingIsLeftOnDisk() throws IOException {
        assertThat(spooled()).as("spooled parts left in " + System.getProperty("java.io.tmpdir")).isSubsetOf(spooledBefore);
    }

    /** The files in Spring's multipart spool directories ({@code spring-multipart-*} under {@code java.io.tmpdir}). */
    private static Set<Path> spooled() throws IOException {
        Set<Path> files = new HashSet<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "spring-multipart-*")) {
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            }
        }
        return files;
    }

    /**
     * A press of photos over 256 KB each is parsed to disk part by part, and on Spring Framework 6.2.2 the multipart
     * parser lost the demand that arrived after a part's file write (spring-framework#34178): the request then hung
     * forever, with no error and no log line. Through this server 19 of 40 such presses hung on 6.2.2. Forty presses of
     * two or three photos, each with its own deadline: one that never answers fails here as a timeout.
     */
    @Test
    void fortyPressesOfSeveralLargePhotosEachAnswer() {
        for (int k = 0; k < 40; k++) {
            int count = 2 + k % 2;
            int size = (6 + (k * 7) % 14) * MB;
            RECEIVED.clear();
            ResponseEntity<String> r = send(files(count, size), true);
            assertThat(r.getStatusCode().value()).as("press " + k + ": " + count + " x " + size + " bytes").isEqualTo(200);
            assertThat(RECEIVED).as("press " + k + " reached the upload whole").hasSize(count);
        }
    }

    @Test
    void a59MegabyteMultipartReachesTheUploadWhole() throws Exception {
        List<byte[]> files = files(3, 59 * MB / 3);
        ResponseEntity<String> r = send(files, true);
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"documents\":[{\"id\":");
        long sent = files.stream().mapToLong(f -> f.length).sum();
        assertThat(RECEIVED.stream().mapToLong(Received::bytes).sum()).as("bytes that reached the upload").isEqualTo(sent);
        List<Received> expected = new ArrayList<>();
        for (byte[] f : files) {
            expected.add(new Received(f.length, sha256(f)));
        }
        assertThat(RECEIVED).as("each file, by its content, in the order sent").containsExactlyElementsOf(expected);
    }

    @Test
    void a61MegabyteMultipartIsTooLargeAndNeverReachesTheUpload() throws Exception {
        ResponseEntity<String> r = send(files(3, 61 * MB / 3), true);
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody()).isEqualTo("{\"reason\":\"TOO_LARGE\"}");
        assertThat(RECEIVED).as("nothing reached the upload").isEmpty();
    }

    /** No Content-Length: the declared-size check cannot see it, so the count while reading must. */
    @Test
    void a61MegabyteChunkedMultipartIsTooLargeToo() throws Exception {
        ResponseEntity<String> r = send(files(3, 61 * MB / 3), false);
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody()).isEqualTo("{\"reason\":\"TOO_LARGE\"}");
        assertThat(RECEIVED).as("nothing reached the upload").isEmpty();
    }

    /**
     * A chunked press far over the cap is refused while it arrives, never received whole first. 500 MB in 20 MB files,
     * each under the file cap, so only the count of the body itself can stop it before the end: the 60 MB check on the
     * joined files ran after Spring had spooled every part to disk (the EC2 root disk, shared with n8n and bddrug), and a
     * status check alone cannot tell the two apart.
     *
     * <p>Two counts, so the test fails on unbounded reading and not only on the status. The server's: what the
     * application pulled off the socket, at most the cap and the one buffer that crossed it. The client's: what it had
     * handed the socket when the answer came, which must be short of the whole press. It is not held to the cap itself:
     * once the application stops reading, reactor-netty discards the rest of the request on the floor (no disk, no heap)
     * while the 413 is on its way, and on loopback that is ~120 MB in the few milliseconds it takes.
     */
    @Test
    void aChunkedPressFarOverTheCapIsRefusedWhileItArrives() {
        AtomicLong emitted = new AtomicLong();
        byte[] megabyte = files(1, MB).get(0);
        int files = 25;
        Flux<DataBuffer> body = Flux.concat(
                        Flux.just(field("patient", MA.toString()), field("upload_key", KEY), field("group", "separate")).map(MultipartWire::wrap),
                        Flux.range(0, files).concatMap(i -> Flux.concat(
                                Mono.fromSupplier(() -> MultipartWire.wrap(filePartHeader(i))),
                                Flux.range(0, 20).map(j -> MultipartWire.wrap(megabyte)),
                                Mono.fromSupplier(() -> MultipartWire.wrap("\r\n".getBytes(StandardCharsets.US_ASCII))))),
                        Mono.fromSupplier(() -> MultipartWire.wrap(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII))))
                .doOnNext(b -> emitted.addAndGet(b.readableByteCount()));

        ResponseEntity<String> r = exchange(body, null, Duration.ofSeconds(60));
        long atAnswer = emitted.get();
        long whole = (long) files * 20 * MB;

        // what was read first: a server that read on and then answered 413 has the right status for the wrong reason
        assertThat(SERVER_READ.get()).as("bytes the server read of a %d-byte press", whole)
                .isLessThanOrEqualTo(new BaymaxProperties().getUpload().getMaxRequestBytes() + MB);
        assertThat(atAnswer).as("bytes the client had sent when the 413 came (of %d)", whole).isLessThan(whole);
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(413);
        assertThat(r.getBody()).isEqualTo("{\"reason\":\"TOO_LARGE\"}");
        assertThat(RECEIVED).as("nothing reached the upload").isEmpty();
    }

    /**
     * A press of two thousand small files ("আলাদা আলাদা" would make a document and a model call of each) is refused as
     * its parts arrive, by name, and the server reads no further: not parsed to its end, nothing uploaded.
     */
    @Test
    void aPressOfThousandsOfFilesIsRefusedAsItsPartsArrive() {
        int files = 2000;
        byte[] small = files(1, 10 * 1024).get(0);
        Flux<DataBuffer> body = Flux.concat(
                Flux.just(field("patient", MA.toString()), field("upload_key", KEY), field("group", "separate")).map(MultipartWire::wrap),
                Flux.range(0, files).concatMap(i -> Flux.just(filePartHeader(i), small, "\r\n".getBytes(StandardCharsets.US_ASCII)).map(MultipartWire::wrap)),
                Mono.fromSupplier(() -> MultipartWire.wrap(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII))));

        ResponseEntity<String> r = exchange(body, null, Duration.ofSeconds(60));

        assertThat(SERVER_READ.get()).as("bytes the server read of a %d-file press", files).isLessThan(5L * MB);
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(400);
        assertThat(r.getBody()).isEqualTo("{\"reason\":\"TOO_MANY_FILES\"}");
        assertThat(RECEIVED).as("nothing reached the upload").isEmpty();
    }

    @Test
    void aSingleFileOverTheFileCapIsTooLarge() throws Exception {
        ResponseEntity<String> r = send(files(1, 26 * MB), true);
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody()).isEqualTo("{\"reason\":\"TOO_LARGE\"}");
        assertThat(RECEIVED).as("nothing reached the upload").isEmpty();
    }

    /** Pseudo-random bytes from a fixed seed, so two files never share content and a swapped order would show. */
    private static List<byte[]> files(int count, int size) {
        Random random = new Random(42);
        List<byte[]> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] f = new byte[size];
            random.nextBytes(f);
            files.add(f);
        }
        return files;
    }

    /**
     * The press as a browser writes it, streamed from the file arrays without a copy: with a Content-Length when
     * {@code declared}, else chunked (a {@code Flux} with no length is sent with {@code Transfer-Encoding: chunked}).
     */
    private static ResponseEntity<String> send(List<byte[]> files, boolean declared) {
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(field("patient", MA.toString()));
        chunks.add(field("upload_key", KEY));
        chunks.add(field("group", "one"));
        for (int i = 0; i < files.size(); i++) {
            chunks.add(filePartHeader(i));
            chunks.add(files.get(i));
            chunks.add("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        chunks.add(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        long length = chunks.stream().mapToLong(c -> c.length).sum();
        Flux<DataBuffer> body = Flux.fromIterable(chunks).concatMap(c -> Flux.range(0, (c.length + MB - 1) / MB)
                .map(i -> MultipartWire.wrap(c, i * MB, Math.min(MB, c.length - i * MB))));
        return exchange(body, declared ? length : null, Duration.ofSeconds(30));
    }

    private static ResponseEntity<String> exchange(Flux<DataBuffer> body, Long length, Duration deadline) {
        WebClient.RequestBodySpec spec = client.post().uri("/app/api/uploads")
                .cookie("baymax_session", "tok").header("X-Medioo", "1").header("Origin", AppApiGuardTest.ORIGIN)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY));
        if (length != null) {
            spec = spec.contentLength(length);
        }
        return spec.body(body, DataBuffer.class).retrieve()
                .onStatus(s -> true, response -> Mono.empty())
                .toEntity(String.class)
                .block(deadline);
    }

    private static byte[] filePartHeader(int i) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"page-" + (i + 1)
                + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** Buffers over the arrays as they are: no copy, so a 500 MB press costs one megabyte of heap. */
    private static final class MultipartWire {
        static DataBuffer wrap(byte[] bytes) {
            return wrap(bytes, 0, bytes.length);
        }

        static DataBuffer wrap(byte[] bytes, int offset, int length) {
            return DefaultDataBufferFactory.sharedInstance.wrap(ByteBuffer.wrap(bytes, offset, length).slice());
        }
    }

    private static byte[] field(String name, String value) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The use case, reduced to what it was handed: one document back, every file recorded by content. */
    static final class RecordingUploads implements UploadUseCase {
        @Override
        public Mono<UploadOptions> uploadOptions(UUID familyId) {
            return Mono.error(new UnsupportedOperationException("not in this test"));
        }

        @Override
        public Mono<Void> admit(UUID familyId, UUID patientId) {
            return Mono.empty();
        }

        @Override
        public Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads) {
            return Mono.error(new UnsupportedOperationException("the JSON route posts with a key"));
        }

        @Override
        public Mono<List<Document>> upload(UUID familyId, UUID patientId, List<Upload> uploads, Grouping grouping, String uploadKey) {
            return Mono.fromCallable(() -> {
                for (Upload u : uploads) {
                    RECEIVED.add(new Received(u.bytes().length, sha256(u.bytes())));
                }
                return List.of(Document.received(patientId, familyId, uploads.size(), NOW).withId(UUID.randomUUID()));
            });
        }
    }
}
