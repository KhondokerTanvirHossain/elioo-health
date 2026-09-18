package com.elioo.baymax.outbound.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The reviewer's side of the gate (DR-13): list pending, approve, reject with a reason. Admin token. */
@Component
@RequiredArgsConstructor
public class ReviewGateHandler {

    private final ReviewGateUseCase gate;

    public Mono<ServerResponse> pending(ServerRequest request) {
        return gate.pending().map(ReviewGateHandler::body).collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(Map.of("pending", list)));
    }

    public Mono<ServerResponse> approve(ServerRequest request) {
        return gate.approve(id(request), reviewer(request)).flatMap(m -> ServerResponse.ok().bodyValue(body(m)));
    }

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> reject(ServerRequest request) {
        UUID id = id(request);
        return request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
                .flatMap(b -> gate.reject(id, reviewer(request), String.valueOf(((Map<String, Object>) b).getOrDefault("reason", ""))))
                .flatMap(m -> ServerResponse.ok().bodyValue(body(m)));
    }

    private static UUID id(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", "message id must be a UUID");
        }
    }

    /** DR-13: reviewer identity is config-shaped, not code — the header names who decided; default is the pilot reviewer. */
    private static String reviewer(ServerRequest request) {
        String h = request.headers().firstHeader("X-Baymax-Reviewer");
        return h == null || h.isBlank() ? "tanvir" : h.trim();
    }

    static Map<String, Object> body(OutboundMessage m) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", m.id().toString());
        b.put("document_id", m.documentId() == null ? null : m.documentId().toString());
        b.put("kind", m.kind().dbValue());
        b.put("urgency", m.urgency().dbValue());
        b.put("urgency_reasons", m.urgencyReasons());
        b.put("body", m.body());
        b.put("gate_status", m.gateStatus().dbValue());
        b.put("reviewer", m.reviewer());
        b.put("reject_reason", m.rejectReason());
        b.put("decided_at", m.decidedAt() == null ? null : m.decidedAt().toString());
        b.put("sent_at", m.sentAt() == null ? null : m.sentAt().toString());
        b.put("created_at", m.createdAt().toString());
        return b;
    }
}
