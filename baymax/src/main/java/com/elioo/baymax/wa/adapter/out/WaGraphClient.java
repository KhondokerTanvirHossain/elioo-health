package com.elioo.baymax.wa.adapter.out;

import com.elioo.baymax.config.BaymaxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The Cloud API calls BMX-10 phase 1 needs: fetch a media descriptor, download its bytes, send a session text.
 *
 * <p><b>The media URL is never logged.</b> A media download URL carries a short-lived access token in its query
 * string, and DR-23 records that query strings reach the production log in clear through {@code IWebFilter} —
 * which only covers inbound requests, so these outbound calls escape it by default. That default is not a
 * control: one {@code log.info("fetching {}", url)} would put a credential in the log. The constraint is that
 * this class logs the media <em>id</em> and the byte count and never the URL, asserted in
 * {@code WaGraphClientTest}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaGraphClient implements com.elioo.baymax.wa.application.port.out.WaMessagingPort {

    private final BaymaxProperties properties;
    private final WebClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WaGraphClient(BaymaxProperties properties) {
        this(properties, WebClient.builder()
                // a lab report photo is easily a few MB; the default 256KB buffer would truncate it
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build());
    }

    WaGraphClient(BaymaxProperties properties, WebClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** What a media id resolves to before the bytes are fetched. */
    public record Media(String url, String mimeType, long sizeBytes, String sha256) {
    }

    private String graph() {
        return "https://graph.facebook.com/" + properties.getWa().getGraphVersion();
    }

    private String token() {
        return properties.getWa().getToken();
    }

    /** Step one of a download: the id is public-ish, the URL it returns is not. */
    public Mono<Media> media(String mediaId) {
        return http.get().uri(graph() + "/" + mediaId)
                .header("Authorization", "Bearer " + token())
                .retrieve().bodyToMono(JsonNode.class)
                .map(node -> new Media(node.path("url").asText(""), node.path("mime_type").asText(""),
                        node.path("file_size").asLong(0), node.path("sha256").asText("")))
                .doOnNext(m -> log.info("[baymax] wa media resolved id={} mime={} bytes={}",
                        mediaId, m.mimeType(), m.sizeBytes()));
    }

    /**
     * Step two: the bytes. The URL is on graph's lookaside host and still needs the bearer token; it is passed
     * through from {@link #media} and never written anywhere.
     */
    public Mono<byte[]> download(Media media, String mediaId) {
        return http.get().uri(media.url())
                .header("Authorization", "Bearer " + token())
                .retrieve().bodyToMono(byte[].class)
                .doOnNext(bytes -> log.info("[baymax] wa media downloaded id={} bytes={}", mediaId, bytes.length));
    }

    /** Convenience: descriptor then bytes, for the one case phase 1 has. */
    @Override
    public Mono<byte[]> downloadMedia(String mediaId) {
        return media(mediaId).flatMap(m -> download(m, mediaId));
    }

    /**
     * A free-form session message, valid only inside the 24-hour window the family's own message opened.
     * Outside it the Cloud API refuses and only an approved template would work — phase 1 has none, so the
     * refusal is surfaced rather than worked around.
     *
     * @return the provider message id (wamid), for correlating the delivery status callback
     */
    @Override
    public Mono<String> sendText(String toNumber, String body) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", toNumber,
                "type", "text",
                "text", Map.of("preview_url", false, "body", body));
        return http.post().uri(graph() + "/" + properties.getWa().getPhoneNumberId() + "/messages")
                .header("Authorization", "Bearer " + token())
                .bodyValue(payload)
                .retrieve().bodyToMono(JsonNode.class)
                .map(node -> node.path("messages").path(0).path("id").asText(""))
                .doOnNext(id -> log.info("[baymax] wa outbound sent wamid={} chars={}", id, body.length()));
    }
}
