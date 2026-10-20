package com.elioo.baymax.wa.adapter.out;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.WaFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private Level levelBefore;
    private final List<String> requestedUris = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        clientLogger = (Logger) LoggerFactory.getLogger(WaGraphClient.class);
        logged = new ListAppender<>();
        logged.start();
        clientLogger.addAppender(logged);
        levelBefore = clientLogger.getLevel();
        clientLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseLogs() {
        clientLogger.detachAppender(logged);
        clientLogger.setLevel(levelBefore);
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

    /** Answers every call with the given status and Meta's error shape, recording the URI and the body it was sent. */
    private WaGraphClient clientAnswering(HttpStatus status) {
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setToken(BEARER);
        props.getWa().setPhoneNumberId("1412074765313019");
        WebClient http = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUris.add(request.url().toString());
                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"error\":{\"message\":\"(#100) Invalid parameter\",\"code\":100}}").build());
                })
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
        String out1 = WaFixtures.wamid("8801700000000", 1);
        WaGraphClient client = clientReturning("{\"messages\":[{\"id\":\"" + out1 + "\"}]}");

        String wamid = client.sendText("+8801700000000", "মায়ের রিপোর্ট দেখে নিলাম").block();

        assertThat(wamid).isEqualTo(out1);
        String log = everythingLogged();
        // it asserted the wamid WAS logged, holding the leak in place; the digest is what correlates now
        assertThat(log).doesNotContain(out1).contains("ref=" + Redact.id(out1));
        assertThat(log).as("a recipient number is not a log line").doesNotContain("+8801700000000");
        assertThat(log).as("message content never reaches the log").doesNotContain("মায়ের রিপোর্ট দেখে নিলাম");
    }

    /** A wamid is base64 of the recipient's number: the sent line carries its digest, never the id or the digits. */
    @Test
    void aSentMessageIsLoggedByDigest() {
        String wamid = WaFixtures.wamid("8801700000000", 3);
        WaGraphClient client = clientReturning("{\"messages\":[{\"id\":\"" + wamid + "\"}]}");

        assertThat(client.sendText("+8801700000000", "ok").block()).isEqualTo(wamid);

        String log = everythingLogged();
        // the base64 body too: a line that dropped the "wamid." prefix still prints the number, and plain digits
        // never appear inside base64, so the digit check alone could not see it
        assertThat(log).as("a wamid in the log is the phone number in the log").doesNotContain(wamid)
                .doesNotContain(wamid.substring("wamid.".length(), "wamid.".length() + 16))
                .doesNotContain("8801700000000");
        assertThat(log).as("the line exists, carrying the digest").contains("ref=" + Redact.id(wamid));
    }

    @Test
    void sendTextPostsToThePhoneNumberIdsMessagesEndpoint() {
        clientReturning("{\"messages\":[{\"id\":\"" + WaFixtures.wamid("8801700000000", 2) + "\"}]}")
                .sendText("+8801700000000", "ok").block();

        assertThat(requestedUris).singleElement().asString()
                .contains("/1412074765313019/messages");
    }

    /**
     * A 200 that names no message id is not an acceptance. It used to emit "" — not empty, not an error — so every
     * caller's "the provider accepted it" branch ran: the sign-in page said "sent", and a release row could carry a
     * blank provider id. One rule, here where every caller meets it: no id, an error, which each caller already
     * treats as not delivered.
     */
    @Test
    void aSendAnsweredWithoutAMessageIdIsAnErrorNeverAnEmptyId() {
        for (String body : List.of("{\"messages\":[]}", "{}", "{\"messages\":[{\"id\":\"\"}]}", "{\"messages\":[{\"id\":\"  \"}]}")) {
            reactor.test.StepVerifier.create(clientReturning(body).sendText("+8801700000000", "ok"))
                    .as(body)
                    .expectErrorSatisfies(e -> assertThat(e.getMessage()).contains("no message id")
                            .doesNotContain("8801700000000").doesNotContain("ok\""))
                    .verify();
        }
    }

    // ---- read receipts and typing (plan PR 10, Task 10.3) --------------------------------------------------------

    /** Meta's documented shape: "status":"read" marks the message read; typing_indicator adds the dots. */
    @Test
    void theReadPayloadMarksReadAndOnlyAddsTypingWhenAsked() {
        String inbound = WaFixtures.wamid("8801700000000", 51);
        assertThat(WaGraphClient.readPayload(inbound, false))
                .isEqualTo(Map.of("messaging_product", "whatsapp", "status", "read", "message_id", inbound));
        assertThat(WaGraphClient.readPayload(inbound, true))
                .containsEntry("typing_indicator", Map.of("type", "text"))
                .containsEntry("status", "read")
                .containsEntry("message_id", inbound)
                .hasSize(4);
    }

    @Test
    void markReadPostsToTheMessagesEndpointOfThePinnedVersion() {
        WaGraphClient client = clientReturning("{\"success\":true}");
        client.markRead(WaFixtures.wamid("8801700000000", 51), true).block();
        assertThat(requestedUris).containsExactly("https://graph.facebook.com/v25.0/1412074765313019/messages");
        assertThat(everythingLogged()).contains("wa read receipt sent")
                .doesNotContain(WaFixtures.wamid("8801700000000", 51)).doesNotContain("8801700000000");
    }

    /** A read receipt is a courtesy: its failure never fails the answer, and its log line carries no wamid. */
    @Test
    void aFailedReadReceiptCompletesEmpty() {
        String inbound = WaFixtures.wamid("8801700000000", 51);
        StepVerifier.create(clientAnswering(HttpStatus.BAD_REQUEST).markRead(inbound, false)).verifyComplete();
        // it really did call Graph, so the clean log is not the clean log of a call that never happened
        assertThat(requestedUris).hasSize(1);
        assertThat(everythingLogged()).contains("wa read receipt failed").contains("status=400")
                .doesNotContain(BEARER)
                .doesNotContain(inbound)
                .doesNotContain(inbound.substring("wamid.".length(), "wamid.".length() + 16))
                .doesNotContain("8801700000000")
                .doesNotContain("Invalid parameter");
    }
}
