package com.elioo.baymax.web.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** {@code GET /me} and {@code GET /patients/{id}/timeline}, session-scoped. */
@Component
@RequiredArgsConstructor
public class TimelineApiHandler {

    private final TimelineUseCase timeline;

    public Mono<ServerResponse> me(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return timeline.me(session.familyId()).flatMap(me -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("family", Map.of("id", me.family().id().toString(), "owner_name", me.family().ownerName(),
                    "plan", me.family().plan().dbValue()));
            body.put("patients", me.patients().stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.patient().id().toString());
                m.put("name", p.patient().name());
                m.put("age", p.patient().age());
                m.put("sex", p.patient().sex().dbValue());
                m.put("owner", p.owner());
                m.put("documents", p.documents());
                m.put("last_document_at", p.lastDocumentAt() == null ? null : p.lastDocumentAt().toString());
                return m;
            }).toList());
            return ServerResponse.ok().bodyValue(body);
        });
    }

    public Mono<ServerResponse> timeline(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"), "patient id");
        String cursor = request.queryParam("cursor").orElse(null);
        return timeline.timeline(session.familyId(), patientId, cursor)
                .flatMap(page -> Flux.fromIterable(page.entries())
                        .concatMap(e -> timeline.imageUrl(session.familyId(), e.documentId(), e.pageOneKey())
                                .map(url -> entry(e, url.toString()))
                                .onErrorResume(err -> Mono.just(entry(e, null))))
                        .collectList()
                        .flatMap(entries -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("documents", entries);
                            body.put("next_cursor", page.nextCursor());
                            return ServerResponse.ok().bodyValue(body);
                        }));
    }

    private static Map<String, Object> entry(TimelineEntry e, String thumbnail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.documentId().toString());
        m.put("doc_date", e.date().toString());
        m.put("doc_date_is_fallback", e.dateIsFallback());
        m.put("document_type", e.documentType());
        m.put("facility", e.facility());
        m.put("status", e.status());
        m.put("counts", Map.of("values", e.values(), "medicines", e.medicines(), "follow_up", e.followUps()));
        m.put("unverified", e.unverified());
        m.put("thumbnail_url", thumbnail);
        return m;
    }

    static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", what + " must be a UUID");
        }
    }
}
