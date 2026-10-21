package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ImageDimensions;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.DocumentPages;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.domain.ResultView;
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
 * {@code GET /app/api/documents/{id}}: one paper's display model ({@link PaperJson}). As the HTML document page reads it
 * ({@code DocumentPages.documentPage}): the status first, which records nothing and is 404 for a paper this family cannot
 * see, so a probe of another family's id records no view; then one read that records the one DOCUMENT_VIEW — the
 * reading view while the paper is RECEIVED or PROCESSING, the result otherwise. A paper that finished between the two
 * reads is sent finished, never as a reading that would wait for a change that already happened; that opening records
 * two DOCUMENT_VIEW rows (the reading view's and the result's), as the HTML page does, accepted for a window of
 * milliseconds rather than a second audit path. The name forms are
 * the viewer's language's ({@link UiCopy#poss}, {@link UiCopy#obj}), so an English viewer never gets a Bangla case ending.
 */
@Component
@RequiredArgsConstructor
public class PaperApiHandler {

    private final ResultUseCase results;
    private final UiCopy copy;
    private final DocumentStorageUseCase storage;

    public Mono<ServerResponse> paper(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = uuid(request.pathVariable("id"));
        return results.status(session.familyId(), documentId)
                .flatMap(status -> !inFlight(status) ? finished(session, documentId)
                        : results.reading(session.familyId(), documentId).flatMap(r -> inFlight(Document.Status.valueOf(r.document().status()))
                                ? Mono.just(PaperJson.reading(r))
                                : finished(session, documentId)))
                .flatMap(body -> {
                    @SuppressWarnings("unchecked")
                    String name = (String) ((Map<String, Object>) body.get("patient")).get("name");
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(PaperJson.withNames(body, copy.poss(lang, name), copy.obj(lang, name)));
                });
    }

    /**
     * The result, then (only for DONE, the one state whose JSON carries crops) the paper's stored image sizes, read
     * once per paper after the result has proved the paper visible. A legacy row is measured here, on its paper's first
     * read (D4b), never when an image is served, so serving an image never costs a ledger query.
     */
    private Mono<Map<String, Object>> finished(WebSession session, UUID documentId) {
        return results.result(session.familyId(), documentId)
                .flatMap(r -> sizes(r, documentId).map(sizes -> PaperJson.of(r, code -> DocumentPages.retakeReasonKey(copy, code), sizes)));
    }

    private Mono<Map<String, ImageDimensions>> sizes(ResultView r, UUID documentId) {
        if (!r.done()) {
            return Mono.just(Map.of());
        }
        return storage.measuredObjectsOf(documentId)
                .filter(o -> o.dimensions().isPresent())
                .collectMap(StoredObject::storageKey, o -> o.dimensions().orElseThrow());
    }

    private static boolean inFlight(Document.Status status) {
        return status == Document.Status.RECEIVED || status == Document.Status.PROCESSING;
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("not_found", "no such paper");
        }
    }
}
