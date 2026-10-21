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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
                new UploadApiHandler(new RecordingUploads(), properties), guard);
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(router)))
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

    @BeforeEach
    void forget() {
        RECEIVED.clear();
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
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(field("patient", MA.toString()));
        chunks.add(field("upload_key", KEY));
        chunks.add(field("group", "one"));
        for (int i = 0; i < files.size(); i++) {
            chunks.add(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"page-" + (i + 1)
                    + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            chunks.add(files.get(i));
            chunks.add("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        chunks.add(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        long length = chunks.stream().mapToLong(c -> c.length).sum();
        Flux<DataBuffer> body = Flux.fromIterable(chunks).concatMap(c -> Flux.range(0, (c.length + MB - 1) / MB)
                .map(i -> (DataBuffer) buffers.wrap(ByteBuffer.wrap(c, i * MB, Math.min(MB, c.length - i * MB)).slice())));
        WebClient.RequestBodySpec spec = client.post().uri("/app/api/uploads")
                .cookie("baymax_session", "tok").header("X-Medioo", "1").header("Origin", AppApiGuardTest.ORIGIN)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY));
        if (declared) {
            spec = spec.contentLength(length);
        }
        return spec.body(body, DataBuffer.class).retrieve()
                .onStatus(s -> true, response -> Mono.empty())
                .toEntity(String.class)
                .block(Duration.ofSeconds(30));
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
