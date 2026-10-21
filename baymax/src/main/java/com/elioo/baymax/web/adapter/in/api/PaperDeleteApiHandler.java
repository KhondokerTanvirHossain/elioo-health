package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deleting a paper, in two deliberate steps (plan B4c):
 * <ol>
 *   <li>{@code GET /app/api/documents/{id}/delete-token} → {@code {confirm_token, expires_at}}: the owner asks to delete
 *       and gets a {@link DeleteConfirmation} for this paper in this session, ten minutes long;</li>
 *   <li>{@code DELETE /app/api/documents/{id}} with {@code {"confirm_token": …}} → 200
 *       {@code {objects_deleted, rows_deleted}}, through {@link TimelineUseCase#deleteDocument}, the one delete every
 *       caller shares (the HTML page and {@code /api/v1} too).</li>
 * </ol>
 * Refusals come in a fixed order, so each tells only what the one before it allowed: 404 {@code NOT_FOUND} for a paper
 * this family cannot see (the same as one that does not exist: ids cannot be probed), then 403 {@code VIEW_ONLY} for a
 * share member, who may see the paper but never delete it, then 400 {@code CONFIRM_REQUIRED} for a token that is
 * missing, another paper's, another session's, tampered or expired. Visibility and ownership are one rule,
 * {@link TimelineUseCase#deletableDocument}, which both routes and the delete itself ask; this class never compares ids.
 * Nothing here is logged beyond the guard's access line, which carries the route pattern, never the id.
 */
@Component
@RequiredArgsConstructor
public class PaperDeleteApiHandler {

    private final TimelineUseCase timeline;
    private final DeleteConfirmation confirmations;

    public Mono<ServerResponse> token(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = PaperStatusApiHandler.documentId(request);
        return timeline.deletableDocument(session.familyId(), documentId)
                .map(d -> confirmations.mint(session.id(), documentId))
                .flatMap(token -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("confirm_token", token.value());
                    body.put("expires_at", token.expiresAt().toString());
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                });
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = PaperStatusApiHandler.documentId(request);
        return timeline.deletableDocument(session.familyId(), documentId)
                .then(request.bodyToMono(Confirm.class).defaultIfEmpty(new Confirm(null)))
                .filter(c -> confirmations.valid(c.confirmToken(), session.id(), documentId))
                .switchIfEmpty(Mono.error(() -> BaymaxException.badRequest("confirm_required",
                        "deleting a paper needs a valid confirm token for it in this session")))
                .flatMap(c -> timeline.deleteDocument(session.familyId(), documentId))
                .flatMap(report -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("objects_deleted", report.objectsDeleted());
                    body.put("rows_deleted", report.rowsDeleted());
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                });
    }

    /** {@code {"confirm_token": "<token>"}}; any other field is ignored. */
    record Confirm(@JsonProperty("confirm_token") String confirmToken) {
    }
}
