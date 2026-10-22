package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.PaintedPaper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master Review Focus 3, the Java side (plan W7): the network dies in the middle of a press, and the sheet's retry, with
 * the key it drew for that press, is still one paper. The real {@link AppApiGuard}, the real {@code UploadService} and
 * intake, Postgres and MinIO, served on a reactor-netty port, because only a socket can be cut: a {@code WebTestClient}
 * bound to a context never streams a body, so it cannot stop half way.
 *
 * <p>The press is three pages as one report. Its first attempt sends its headers (a whole Content-Length) and half of
 * its body, and the socket is closed, as a phone's dies. Then the whole press is sent with the same {@code upload_key},
 * and sent again (the sheet retries when an answer is lost after the bytes arrived). Counted on the database itself:
 * the cut-off attempt left no row, and after both whole sends there is exactly one document for the patient, under this
 * key, and it is the id each answer named.
 */
@Testcontainers
class UploadCutOffAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private static final String KEY = "6c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String BOUNDARY = "----mediooCutOffBoundary7Kq4";

    @Test
    void aPressCutOffMidBodyThenRetriedWithItsKeyIsOneDocument() {
        AppApiContext.runner(POSTGRES, MINIO).run(context -> {
            FamilyAccount family = AppApiContext.family(context, "+8801700000631", "Tanvir");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            String token = AppApiContext.session(context, family.id());
            byte[] body = press(ma.id(), List.of(PaintedPaper.paintedPage(), page(new Color(20, 160, 60)), page(new Color(120, 120, 120))));

            BodyWatch watch = new BodyWatch();
            HttpHandler app = WebHttpHandlerBuilder.applicationContext(context).filters(f -> f.add(0, watch)).build();
            DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle(new ReactorHttpHandlerAdapter(app)).bindNow();
            LoopResources loops = LoopResources.create("cut-off-client", 1, true);
            try {
                // the first attempt: every header, a Content-Length for the whole press, half its bytes, then the socket dies
                try (Socket socket = new Socket("127.0.0.1", server.port())) {
                    OutputStream out = socket.getOutputStream();
                    out.write(head(token, body.length));
                    out.write(body, 0, body.length / 2);
                    out.flush();
                    Thread.sleep(500);
                }
                Thread.sleep(1500);
                assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'"))
                        .as("documents after the cut-off attempt").isZero();
                // Falsifiable (W7 review): the cut-off request got past the guard and into the multipart reader (its body
                // was subscribed), and its body ended without completing. A request refused at the guard, rejected for a
                // malformed head or never read would leave no row as well, and fails here instead.
                assertThat(watch.first()).as("the cut-off request on the upload route: its body read, then cut")
                        .isIn("read,error", "read,cancel");

                WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create().runOn(loops))).build();
                List<String> retried = ids(send(client, token, body));
                List<String> again = ids(send(client, token, body));

                assertThat(count("select count(*) from baymax.document where patient_id = '" + ma.id() + "'"))
                        .as("documents for the patient after the retries").isEqualTo(1);
                // V21 stores the key with the press's digest after it (DocumentIntakeService.pressKey): key, ':', digest
                List<String> keyed = rows("select id::text from baymax.document where upload_key like '" + KEY + ":%'");
                assertThat(keyed).as("documents under this press's key").hasSize(1);
                assertThat(retried).as("the retry's answer is that row").containsExactlyElementsOf(keyed);
                assertThat(again).as("the second retry's answer is that row").containsExactlyElementsOf(keyed);
            } finally {
                server.disposeNow();
                loops.disposeLater().block(Duration.ofSeconds(10));
            }
        });
    }

    /**
     * What happened to the first request on {@code POST /app/api/uploads}'s body: {@code read} once the handler (past the
     * guard) subscribed to it, then how it ended ({@code complete}, {@code error} or {@code cancel}), comma-joined.
     */
    static final class BodyWatch implements WebFilter {
        private final AtomicInteger seen = new AtomicInteger();
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (!exchange.getRequest().getPath().value().equals("/app/api/uploads") || seen.getAndIncrement() > 0) {
                return chain.filter(exchange);
            }
            ServerHttpRequest watched = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return super.getBody()
                            .doOnSubscribe(s -> events.add("read"))
                            .doOnComplete(() -> events.add("complete"))
                            .doOnError(e -> events.add("error"))
                            .doOnCancel(() -> events.add("cancel"));
                }
            };
            return chain.filter(exchange.mutate().request(watched).build());
        }

        String first() {
            return String.join(",", events);
        }
    }

    private static byte[] head(String token, int length) {
        return ("POST /app/api/uploads HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cookie: baymax_session=" + token + "\r\n"
                + "X-Medioo: 1\r\nOrigin: " + AppApiContext.ORIGIN + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static ResponseEntity<String> send(WebClient client, String token, byte[] body) {
        return client.post().uri("/app/api/uploads").cookie("baymax_session", token).header("X-Medioo", "1")
                .header("Origin", AppApiContext.ORIGIN).header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .bodyValue(body).retrieve().onStatus(s -> true, r -> Mono.empty()).toEntity(String.class)
                .block(Duration.ofSeconds(60));
    }

    /** Every {@code documents[*].id} of a 200, in order, read from the body by its field. */
    private static List<String> ids(ResponseEntity<String> r) {
        assertThat(r.getStatusCode().value()).as("the press answered " + r.getBody()).isEqualTo(200);
        Matcher m = Pattern.compile("\\{\"id\":\"([0-9a-f-]{36})\"\\}").matcher(r.getBody());
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        assertThat(ids).as("documents in " + r.getBody()).isNotEmpty();
        return ids;
    }

    /** The press as the sheet sends it: the patient, the key, one report, three pages. */
    private static byte[] press(UUID patient, List<byte[]> pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, "patient", patient.toString());
        field(out, "upload_key", KEY);
        field(out, "group", "one");
        for (int i = 0; i < pages.size(); i++) {
            out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"page-" + (i + 1)
                    + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(pages.get(i));
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String name, String value) throws Exception {
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** A page of one colour at the painted page's size: other bytes, still a page intake reads. */
    private static byte[] page(Color colour) throws Exception {
        BufferedImage image = new BufferedImage(PaintedPaper.PAGE_W, PaintedPaper.PAGE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, PaintedPaper.PAGE_W, PaintedPaper.PAGE_H);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int count(String sql) throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
