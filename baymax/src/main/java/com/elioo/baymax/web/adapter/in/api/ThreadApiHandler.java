package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.ThreadUseCase;
import com.elioo.baymax.web.domain.ThreadItem;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /app/api/patients/{id}/thread?cursor=} (plan W5-api): the চ্যাট thread as {@link ThreadJson}, newest first,
 * {@link ThreadUseCase#PAGE_SIZE} entries a page. A paper's {@code card} is built exactly as {@code GET
 * /app/api/documents/{id}} builds its body ({@link PaperApiHandler#body}): the same JSON, the same stored crop sizes, the
 * same name forms in the viewer's language, so React draws one shape; unlike that route, it records no view. A patient
 * this family cannot see is 404 {@code NOT_FOUND}; a path id that is not a UUID, or a cursor that is not a previous
 * page's, is 400 {@code INVALID}. Nothing here logs: the guard writes the access line by route pattern, and a message
 * body is the family's text.
 */
@Component
@RequiredArgsConstructor
public class ThreadApiHandler {

    /** Cards turned into JSON at once (each may read its paper's stored crop sizes). */
    static final int CARD_CONCURRENCY = 4;

    private final ThreadUseCase threads;
    private final UiCopy copy;
    private final DocumentStorageUseCase storage;

    public Mono<ServerResponse> thread(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID patientId = patientId(request);
        String cursor = request.queryParam("cursor").orElse(null);
        // plan CHAT-api: asks only when asked for, so the web deployed today (which reads an unknown kind as a paper) is untouched
        boolean asks = "1".equals(request.queryParam("asks").orElse(null));
        return threads.thread(session.familyId(), patientId, cursor, asks)
                .flatMap(page -> Flux.fromIterable(page.items())
                        .flatMapSequential(entry -> item(entry, lang), CARD_CONCURRENCY)
                        .collectList()
                        .map(items -> ThreadJson.page(items, page.nextCursor())))
                .flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    private Mono<Map<String, Object>> item(ThreadUseCase.Entry entry, Lang lang) {
        return switch (entry.item()) {
            case ThreadItem.Paper paper -> PaperApiHandler.body(entry.card(), lang, copy, storage).map(card -> ThreadJson.paper(paper, card));
            case ThreadItem.Message message -> Mono.just(ThreadJson.message(message));
            case ThreadItem.Ask ask -> Mono.just(ThreadJson.ask(ask));
        };
    }

    /** The {@code {id}} path variable; one that is not a UUID is the client's mistake, 400 (plan W5-api). */
    private static UUID patientId(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", "the patient id is not a UUID");
        }
    }
}
