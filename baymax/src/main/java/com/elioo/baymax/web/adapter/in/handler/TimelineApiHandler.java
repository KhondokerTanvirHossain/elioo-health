package com.elioo.baymax.web.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.PatientJson;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
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

/**
 * {@code GET /me} and {@code GET /patients/{id}/timeline}, session-scoped. The timeline takes {@code cursor} (a previous
 * page's opaque {@code next_cursor}) and {@code type} ({@code lab}, {@code rx}; anything else is every paper).
 */
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
            body.put("patients", me.patients().stream().map(PatientJson::fields).toList());
            return ServerResponse.ok().bodyValue(body);
        });
    }

    public Mono<ServerResponse> timeline(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"), "patient id");
        String cursor = request.queryParam("cursor").orElse(null);
        TimelineFilter filter = TimelineFilter.fromParam(request.queryParam("type").orElse(null));
        return timeline.timeline(session.familyId(), patientId, cursor, filter)
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
        m.put("doc_date_uncertain", e.dateUncertain());
        m.put("document_type", e.documentType());
        m.put("facility", e.facility());
        m.put("status", e.status());
        m.put("counts", Map.of("values", e.values(), "medicines", e.medicines(), "follow_up", e.followUps()));
        m.put("unverified", e.unverified());
        // C2: the stored F1 level (null = not yet assessed), the F2 outside count, the latest explanation's gate status,
        // and the F5 code of a retake — the code, never the developer prose
        m.put("urgency", e.urgency() == null ? null : e.urgency().dbValue());
        m.put("outside", e.outside());
        m.put("review_state", e.reviewState());
        m.put("retake_reason_code", e.retakeReasonCode());
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
