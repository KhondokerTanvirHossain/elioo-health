package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.config.BaymaxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * The public WhatsApp webhook (BMX-10 phase 1).
 *
 * <p>{@code GET} answers Meta's verification handshake: echo {@code hub.challenge} as plain text when
 * {@code hub.verify_token} matches, 403 otherwise. {@code POST} carries inbound messages and delivery
 * statuses; the body is read as raw bytes and its {@code X-Hub-Signature-256} checked before anything parses
 * it, because the HMAC is over the bytes as received.
 *
 * <p>Logs carry message ids, direction and outcome — never message content and never a sender's number in
 * clear, which is why the number is only ever logged through {@link #maskedNumber}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaWebhookHandler {

    private final BaymaxProperties properties;
    private final ObjectMapper json;

    /** Meta's handshake. A wrong or missing token is 403 — the endpoint never confirms what the right one is. */
    public Mono<ServerResponse> verify(ServerRequest request) {
        String mode = request.queryParam("hub.mode").orElse("");
        String token = request.queryParam("hub.verify_token").orElse("");
        String challenge = request.queryParam("hub.challenge").orElse("");
        String expected = properties.getWa().getVerifyToken();

        if (expected == null || expected.isBlank()) {
            log.error("[baymax] wa webhook: verify token is blank — the handshake cannot succeed (fail closed)");
            return ServerResponse.status(403).bodyValue("forbidden");
        }
        // constant-time: the handshake is public and a wrong token must not leak how wrong it was
        boolean ok = "subscribe".equals(mode)
                && java.security.MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            log.warn("[baymax] wa webhook: handshake rejected mode={} tokenMatched=false", mode);
            return ServerResponse.status(403).bodyValue("forbidden");
        }
        log.info("[baymax] wa webhook: handshake ok");
        return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValue(challenge);
    }

    /**
     * Inbound callbacks. Always 200 once the signature is good: Meta retries anything else, and a retry storm
     * over a message we have already stored is worse than dropping one. Unsigned or mismatched is 403 and
     * nothing is parsed.
     */
    public Mono<ServerResponse> receive(ServerRequest request) {
        String signature = request.headers().firstHeader("X-Hub-Signature-256");
        return request.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> {
                    if (!WaSignature.valid(signature, body, properties.getWa().getAppSecret())) {
                        log.warn("[baymax] wa webhook: rejected unsigned or mismatched callback bytes={} signaturePresent={}",
                                body.length, signature != null);
                        return ServerResponse.status(403).bodyValue("forbidden");
                    }
                    return handle(body).then(ServerResponse.ok().bodyValue("ok"));
                });
    }

    /**
     * One callback may carry several entries, each with several changes, each with several messages — the
     * shape is nested and every level is optional, so each is walked defensively rather than assumed.
     */
    private Mono<Void> handle(byte[] body) {
        return Mono.fromCallable(() -> json.readTree(body))
                .doOnNext(this::logCallback)
                .onErrorResume(e -> {
                    log.error("[baymax] wa webhook: signed callback did not parse as JSON: {}", e.toString());
                    return Mono.empty();
                })
                .then();
    }

    private void logCallback(JsonNode root) {
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode message : value.path("messages")) {
                    log.info("[baymax] wa inbound id={} type={} from={}",
                            message.path("id").asText(""), message.path("type").asText(""),
                            maskedNumber(message.path("from").asText("")));
                }
                for (JsonNode status : value.path("statuses")) {
                    log.info("[baymax] wa status id={} status={} recipient={}",
                            status.path("id").asText(""), status.path("status").asText(""),
                            maskedNumber(status.path("recipient_id").asText("")));
                }
            }
        }
    }

    /**
     * Last four digits only. A phone number is the one identifier that appears nowhere but {@code
     * family_account} (BMX-5), and a log line is not that table.
     */
    static String maskedNumber(String number) {
        if (number == null || number.length() < 4) {
            return "****";
        }
        return "****" + number.substring(number.length() - 4);
    }
}
