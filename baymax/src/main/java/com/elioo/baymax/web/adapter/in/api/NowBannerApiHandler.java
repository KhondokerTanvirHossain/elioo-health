package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.NowBannerUseCase;
import com.elioo.baymax.web.domain.NowBanner;
import com.elioo.baymax.web.domain.NowSeen;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /app/api/now-banner} and {@code PUT /app/api/now-banner/ack} (plan B3, DR-41 item 5). The answer is
 * {@code {"show", "line_key": "urgency.now", "call_999", "documents": [...], "seen": [...]}}: {@code seen} lists every
 * item, acknowledged or not, and the client sends it back unchanged as {@code {"seen": [...]}}. The reasons digest, never
 * the reasons themselves, leaves Java. The line is the shipped {@code urgency.now} sentence, verbatim (Part B decision
 * 3); the patient's name is the card title's, which React takes from {@code /app/api/me}. A body that is not a list of
 * {@code {document_id, urgency, reasons_digest, release_state}} is 400 {@code INVALID}.
 */
@Component
@RequiredArgsConstructor
public class NowBannerApiHandler {

    private final NowBannerUseCase banners;

    public Mono<ServerResponse> banner(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return banners.banner(session.familyId()).flatMap(NowBannerApiHandler::answer);
    }

    public Mono<ServerResponse> acknowledge(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return request.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(() -> invalid("a body is required")))
                .map(NowBannerApiHandler::seen)
                .flatMap(seen -> banners.acknowledge(session.familyId(), seen))
                .flatMap(NowBannerApiHandler::answer);
    }

    /** {@code {"seen": [{document_id, urgency, reasons_digest, release_state}, ...]}}; anything else is INVALID. */
    static List<NowSeen> seen(JsonNode body) {
        JsonNode list = body == null ? null : body.get("seen");
        if (list == null || !list.isArray()) {
            throw invalid("seen must be a list");
        }
        List<NowSeen> out = new ArrayList<>();
        for (JsonNode entry : list) {
            if (!entry.isObject()) {
                throw invalid("each seen entry is an object");
            }
            out.add(new NowSeen(uuid(text(entry, "document_id")), null, text(entry, "urgency"), text(entry, "reasons_digest"),
                    text(entry, "release_state")));
        }
        return out;
    }

    private static String text(JsonNode entry, String field) {
        JsonNode v = entry.get(field);
        if (v == null || !v.isTextual()) {
            throw invalid("seen entries carry " + field);
        }
        return v.asText();
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("document_id must be a UUID");
        }
    }

    private static BaymaxException invalid(String why) {
        return BaymaxException.badRequest("invalid_request", why);
    }

    private static Mono<ServerResponse> answer(NowBanner banner) {
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> seen = new ArrayList<>();
        for (NowBanner.Item item : banner.documents()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("document_id", item.seen().documentId().toString());
            d.put("patient_id", item.seen().patientId() == null ? null : item.seen().patientId().toString());
            d.put("document_type", item.documentType());
            d.put("date", item.date().toString());
            d.put("date_is_upload_day", item.dateIsUploadDay());
            d.put("date_uncertain", item.dateUncertain());
            d.put("acked", item.acked());
            documents.add(d);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("document_id", item.seen().documentId().toString());
            s.put("urgency", item.seen().urgency());
            s.put("reasons_digest", item.seen().reasonsDigest());
            s.put("release_state", item.seen().releaseState());
            seen.add(s);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("show", banner.show());
        body.put("line_key", "urgency.now");
        body.put("call_999", banner.call999());
        body.put("documents", documents);
        body.put("seen", seen);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
