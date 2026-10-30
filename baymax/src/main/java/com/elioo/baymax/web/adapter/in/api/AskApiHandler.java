package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.companion.application.port.in.AskUseCase;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * {@code POST /app/api/patients/{id}/ask} (plan CHAT-api, PO ruling P7, DR-50): a session route under the guard's CSRF
 * ({@code X-Medioo: 1}, Origin, a JSON body). Body {@code {"question": string}}; the answer is {@link AskJson}. A path id
 * that is not a UUID, a body that is not that JSON object, or a missing, blank or over-long question is 400 {@code INVALID}
 * and stores nothing; a patient this account cannot see is 404 {@code NOT_FOUND}. The language is the page's
 * ({@code baymax_lang}). Nothing here logs: the question is the family's own words, and the guard writes the access line by
 * route pattern.
 */
@Component
@RequiredArgsConstructor
public class AskApiHandler {

    private final AskUseCase asks;

    public Mono<ServerResponse> ask(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID patientId = patientId(request);
        return request.bodyToMono(JsonNode.class)
                .onErrorMap(e -> !(e instanceof BaymaxException), e -> BaymaxException.badRequest("invalid_request", "the body is not JSON"))
                .filter(body -> body.isObject() && body.path("question").isTextual())
                .map(body -> body.get("question").asText())
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "question is required")))
                .flatMap(question -> asks.ask(session.familyId(), patientId, question, lang.code))
                .flatMap(answer -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(AskJson.of(answer)));
    }

    /** The {@code {id}} path variable; one that is not a UUID is the client's mistake, 400. */
    private static UUID patientId(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", "the patient id is not a UUID");
        }
    }
}
