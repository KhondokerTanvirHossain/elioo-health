package com.elioo.baymax.extraction.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.RecropUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code POST /admin/documents/{id}/recrop} and {@code POST /admin/documents/recrop} (all DONE documents). */
@Component
@RequiredArgsConstructor
public class RecropHandler {

    private final RecropUseCase recrop;

    public Mono<ServerResponse> one(ServerRequest request) {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.badRequest("invalid_request", "document id must be a UUID"));
        }
        return recrop.recrop(id).flatMap(r -> ServerResponse.ok().bodyValue(body(r)));
    }

    public Mono<ServerResponse> all(ServerRequest request) {
        return recrop.recropAll().map(RecropHandler::body).collectList()
                .flatMap(reports -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("documents", reports.size());
                    summary.put("recropped", reports.stream().filter(r -> "recropped".equals(r.get("outcome"))).count());
                    summary.put("unverified_total", reports.stream().mapToInt(r -> (Integer) r.get("unverified")).sum());
                    summary.put("reports", reports);
                    return ServerResponse.ok().bodyValue(summary);
                });
    }

    private static Map<String, Object> body(RecropUseCase.RecropReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_id", r.documentId().toString());
        m.put("outcome", r.outcome());
        m.put("values", r.values());
        m.put("medicines", r.medicines());
        m.put("follow_up", r.followUps());
        m.put("clinical_context", r.context());
        m.put("unverified", r.unverified());
        return m;
    }
}
