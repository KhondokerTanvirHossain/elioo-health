package com.elioo.baymax.wa.adapter.out;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-23 constraint, asserted rather than commented: a media download URL carries a short-lived access token in
 * its query string, and nothing in this client may write it to a log. The test captures what the logger
 * actually emitted — grepping the source for {@code log.info(url)} would be a proxy for the thing that matters.
 *
 * <p>HTTP is stubbed with an {@code ExchangeFunction}, the project's mock-the-boundary idiom, so no server and
 * no new dependency.
 */
class WaGraphClientTest {

    private static final String TOKEN_IN_URL = "ATxlKn9SECRETMEDIATOKEN0099";
    private static final String MEDIA_URL =
            "https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=123&hash=" + TOKEN_IN_URL;
    private static final String BEARER = "EAA" + "x".repeat(200);

    private ListAppender<ILoggingEvent> logged;
    private Logger clientLogger;
    private final List<String> requestedUris = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        clientLogger = (Logger) LoggerFactory.getLogger(WaGraphClient.class);
        logged = new ListAppender<>();
        logged.start();
        clientLogger.addAppender(logged);
        clientLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseLogs() {
        clientLogger.detachAppender(logged);
    }

    /** Answers each call in turn with the given bodies, recording the URI it was asked for. */
    private WaGraphClient clientReturning(String... bodies) {
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setToken(BEARER);
        props.getWa().setPhoneNumberId("1412074765313019");
        List<String> queue = new ArrayList<>(List.of(bodies));
        WebClient http = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUris.add(request.url().toString());
                    String body = queue.isEmpty() ? "{}" : queue.remove(0);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", body.startsWith("{")
                                    ? MediaType.APPLICATION_JSON_VALUE : MediaType.IMAGE_JPEG_VALUE)
                            .body(body).build());
                })
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return new WaGraphClient(props, http);
    }

    private String everythingLogged() {
        return String.join("\n", logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void theMediaUrlAndItsTokenNeverReachTheLog() {
        WaGraphClient client = clientReturning(
                "{\"url\":\"" + MEDIA_URL + "\",\"mime_type\":\"image/jpeg\",\"file_size\":12,\"sha256\":\"abc\"}",
                "JPEGBYTES123");

        byte[] bytes = client.downloadMedia("MEDIA-ID-1").block();
        assertThat(bytes).isNotNull().hasSize(12);

        // it really did fetch the signed URL — otherwise this assertion would pass for the wrong reason
        assertThat(requestedUris).anyMatch(u -> u.contains(TOKEN_IN_URL));

        String log = everythingLogged();
        assertThat(log).as("the media URL must never be logged").doesNotContain(MEDIA_URL);
        assertThat(log).as("nor the access token inside it").doesNotContain(TOKEN_IN_URL);
        assertThat(log).as("nor any lookaside host fragment").doesNotContain("lookaside");
        // what it should say instead: the id and the size, which an operator needs and neither of which is secret
        assertThat(log).contains("MEDIA-ID-1").contains("bytes=12");
    }

    @Test
    void theBearerTokenNeverReachesTheLog() {
        WaGraphClient client = clientReturning(
                "{\"url\":\"" + MEDIA_URL + "\",\"mime_type\":\"image/jpeg\",\"file_size\":12,\"sha256\":\"abc\"}",
                "JPEGBYTES123");
        client.downloadMedia("MEDIA-ID-2").block();

        assertThat(everythingLogged()).doesNotContain(BEARER).doesNotContain("Bearer");
    }

    @Test
    void sendTextReturnsTheProviderMessageIdAndLogsNeitherBodyNorNumber() {
        WaGraphClient client = clientReturning("{\"messages\":[{\"id\":\"wamid.OUT1\"}]}");

        String wamid = client.sendText("+8801793399171", "মায়ের রিপোর্ট দেখে নিলাম").block();

        assertThat(wamid).isEqualTo("wamid.OUT1");
        String log = everythingLogged();
        assertThat(log).contains("wamid.OUT1");
        assertThat(log).as("a recipient number is not a log line").doesNotContain("+8801793399171");
        assertThat(log).as("message content never reaches the log").doesNotContain("মায়ের রিপোর্ট দেখে নিলাম");
    }

    @Test
    void sendTextPostsToThePhoneNumberIdsMessagesEndpoint() {
        clientReturning("{\"messages\":[{\"id\":\"wamid.OUT2\"}]}")
                .sendText("+8801793399171", "ok").block();

        assertThat(requestedUris).singleElement().asString()
                .contains("/1412074765313019/messages");
    }
}
