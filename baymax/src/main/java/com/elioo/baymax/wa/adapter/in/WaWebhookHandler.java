package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The public WhatsApp webhook (BMX-10 phase 1).
 *
 * <p>{@code GET} answers Meta's verification handshake: echo {@code hub.challenge} as plain text when
 * {@code hub.verify_token} matches, 403 otherwise. {@code POST} carries inbound messages and delivery
 * statuses; the body is read as raw bytes and its {@code X-Hub-Signature-256} checked before anything parses
 * it, because the HMAC is over the bytes as received.
 *
 * <p>Logs carry message-id digests, direction and outcome — never message content and never a sender's number
 * in clear, which is why the number is only ever logged through {@link #maskedNumber} and a wamid (base64 of the
 * number) only through {@link Redact#id}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaWebhookHandler {

    private final BaymaxProperties properties;
    private final ObjectMapper json;
    private final com.elioo.baymax.wa.application.service.WaIntakeService intake;

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
     * Inbound callbacks (F9a). 200 once every message in the callback is <b>claimed</b> — recorded under HMAC(wamid) in
     * {@code inbound_message} — and dispatched; a message already claimed (Meta re-delivering it) is acknowledged and
     * ignored, so the family hears one reply. If a claim fails (database down, HMAC secret missing) the answer is 503:
     * nothing was recorded, so nothing is acknowledged, Meta retries for up to a week, and the claim makes that retry
     * harmless. A signed body that is not JSON is still 200, because a retry will never parse either. Unsigned or
     * mismatched is 403 and nothing is parsed.
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
                    return handle(body)
                            .then(Mono.defer(() -> ServerResponse.ok().bodyValue("ok")))
                            .onErrorResume(e -> {
                                // nothing was recorded, so nothing is acknowledged: Meta retries, and the claim makes that
                                // safe. Class name only: a driver's message can quote the row it failed to write.
                                log.error("[baymax] wa webhook: inbound could not be recorded, asking Meta to retry error={}",
                                        e.getClass().getSimpleName());
                                return ServerResponse.status(503).bodyValue("retry");
                            });
                });
    }

    /**
     * One callback may carry several entries, each with several changes, each with several messages — the
     * shape is nested and every level is optional, so each is walked defensively rather than assumed.
     *
     * <p>Each message is claimed in the request path, one at a time, and a failed claim fails the request (503). Its
     * handling then runs detached: Meta retries anything that is not a prompt 200, and a download plus extraction is far
     * slower than its timeout; the family hears back over WhatsApp, not over this response. No type is dropped here;
     * the intake answers every one (Phase 0).
     */
    private Mono<Void> handle(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (java.io.IOException e) {
            // a Jackson parse error quotes the body it choked on, which is patient text: class name only
            log.error("[baymax] wa webhook: signed callback did not parse as JSON error={}", e.getClass().getSimpleName());
            return Mono.empty();   // a retry will never parse either
        }
        if (root == null) {
            return Mono.empty();
        }
        logCallback(root);
        List<WaInbound> messages = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode m : change.path("value").path("messages")) {
                    messages.add(parse(m));
                }
            }
        }
        return Flux.fromIterable(messages)
                .concatMap(in -> Mono.defer(() -> intake.claim(in))
                        .doOnNext(claimed -> subscribe(claimed))
                        .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                                "[baymax] wa inbound duplicate ref={} ignored (already claimed)", Redact.id(in.providerMessageId())))))
                .then();
    }

    /** Meta's message JSON → WaInbound. Every level is optional, so every read has a default. */
    static WaInbound parse(JsonNode m) {
        String meta = m.path("type").asText("");
        WaInbound.Type type = WaInbound.Type.of(meta);
        String text = switch (type) {
            case TEXT -> m.path("text").path("body").asText("");
            case REACTION -> m.path("reaction").path("emoji").asText("");
            case INTERACTIVE -> {
                JsonNode i = m.path("interactive");
                JsonNode reply = i.has("button_reply") ? i.path("button_reply") : i.path("list_reply");
                yield reply.path("title").asText(reply.path("id").asText(""));
            }
            case BUTTON -> m.path("button").path("text").asText(m.path("button").path("payload").asText(""));
            default -> "";
        };
        JsonNode media = m.path(meta);   // image | document | audio | video | sticker carry {id, mime_type}
        long ts = m.path("timestamp").asLong(0);   // Meta sends epoch seconds as a string; PR 8 stores it
        return new WaInbound(m.path("id").asText(""), m.path("from").asText(""), type, text,
                media.path("id").asText(""), media.path("mime_type").asText(""), ts == 0 ? null : Instant.ofEpochSecond(ts));
    }

    /** The claimed message's handling, detached from the HTTP response. */
    private void subscribe(WaInbound in) {
        Mono.defer(() -> intake.handle(in))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> log.info("[baymax] wa intake done ref={} outcome={} from={}",
                                Redact.id(in.providerMessageId()), outcome, maskedNumber(in.from())),
                        error -> log.error("[baymax] wa intake errored ref={} from={} error={}",
                                Redact.id(in.providerMessageId()), maskedNumber(in.from()), error.getClass().getSimpleName()));
    }

    private void logCallback(JsonNode root) {
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode message : value.path("messages")) {
                    log.info("[baymax] wa inbound ref={} type={} from={}",
                            Redact.id(message.path("id").asText("")), message.path("type").asText(""),
                            maskedNumber(message.path("from").asText("")));
                }
                for (JsonNode status : value.path("statuses")) {
                    log.info("[baymax] wa status ref={} status={} recipient={}",
                            Redact.id(status.path("id").asText("")), status.path("status").asText(""),
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
