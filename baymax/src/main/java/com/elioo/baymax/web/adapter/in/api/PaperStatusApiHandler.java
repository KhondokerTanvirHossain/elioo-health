package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /app/api/documents/{id}/status} → {@code {"status": "RECEIVED"|"PROCESSING"|"DONE"|"NEEDS_RETAKE"|"FAILED"}}:
 * what the reading screen polls. It reads through {@link ResultUseCase#status}, which decides visibility in SQL and
 * records nothing — never {@code result} or {@code reading}, which each record a DOCUMENT_VIEW: a poll every few
 * seconds would otherwise write an audit row every few seconds. A paper this family cannot see is 404
 * {@code NOT_FOUND}, the same as one that does not exist, so ids cannot be probed.
 */
@Component
@RequiredArgsConstructor
public class PaperStatusApiHandler {

    private final ResultUseCase results;

    public Mono<ServerResponse> status(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = documentId(request);
        return results.status(session.familyId(), documentId)
                .flatMap(status -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("status", status.name())));
    }

    /** The {@code {id}} path variable; one that is not a UUID names no paper, so it is the same 404 as a missing one. */
    static UUID documentId(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("document_not_found", "no such document");
        }
    }
}
