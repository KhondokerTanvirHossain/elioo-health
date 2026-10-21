package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ImageDimensions;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.DocumentPages;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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
@Slf4j
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
                .flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(named(body, lang, copy)));
    }

    /**
     * Plan W5-api: one paper's body from a view already read ({@code ResultUseCase.card}: a {@link ResultView} or a
     * {@link ReadingView}), built exactly as this route builds it — the same JSON, the same stored crop sizes, the same
     * name forms — so the চ্যাট thread's card is this route's body and nothing else. Reads no view and records none.
     */
    static Mono<Map<String, Object>> body(Object view, Lang lang, UiCopy copy, DocumentStorageUseCase storage) {
        Mono<Map<String, Object>> body = switch (view) {
            case ResultView r -> finishedBody(r, copy, storage);
            case ReadingView r -> Mono.just(PaperJson.reading(r));
            default -> Mono.error(new IllegalStateException("not a paper view: " + view.getClass().getSimpleName()));
        };
        return body.map(b -> named(b, lang, copy));
    }

    /** The patient's name forms in the viewer's language ({@link UiCopy#poss}, {@link UiCopy#obj}), added to a built body. */
    static Map<String, Object> named(Map<String, Object> body, Lang lang, UiCopy copy) {
        @SuppressWarnings("unchecked")
        String name = (String) ((Map<String, Object>) body.get("patient")).get("name");
        return PaperJson.withNames(body, copy.poss(lang, name), copy.obj(lang, name));
    }

    /**
     * {@code GET /app/api/documents/{id}/values/{obs}} (plan W6-api): one value's sheet ({@link PaperJson#valueSheet}), read
     * in the HTML sheet's order ({@code DocumentPages.valueSheetPage}). The earlier readings first, which records nothing
     * and is 404 for another family and for an observation that is not one of THIS paper's values, so a probe records no
     * view; then the status, which records nothing either, so a row stored on a read the system rejected (NEEDS_RETAKE,
     * FAILED), which the paper's JSON never shows, opens no sheet; then the result, the one DOCUMENT_VIEW. Then the
     * stored crop sizes: this paper's, and each earlier paper's for its own crop (they are the same patient's, proved by
     * the earlier read), each bounded and never failing the answer after the view was recorded.
     */
    public Mono<ServerResponse> valueSheet(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = uuid(request.pathVariable("id"));
        UUID observationId = uuid(request.pathVariable("obs"));
        UUID family = session.familyId();
        return results.earlierReadings(family, documentId, observationId).collectList()
                .flatMap(earlier -> results.status(family, documentId)
                        .flatMap(status -> status != Document.Status.DONE ? Mono.<ResultView>error(notFound())
                                : results.result(family, documentId))
                        .flatMap(r -> !r.done() ? Mono.error(notFound())
                                : Mono.zip(sizes(r, documentId), earlierSizes(earlier))
                                        .map(s -> PaperJson.valueSheet(r, observationId, earlier, s.getT1(), s.getT2(),
                                                copy.poss(lang, r.patient().name())))))
                .flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    /** Each earlier paper's stored crop sizes, by document; a paper whose sizes cannot be read is sent without them. */
    private Mono<Map<UUID, Map<String, ImageDimensions>>> earlierSizes(List<EarlierReading> earlier) {
        return Flux.fromIterable(earlier.stream().map(EarlierReading::documentId).distinct().toList())
                .flatMap(d -> storage.measuredObjectsOf(d, ObjectKind.CROP)
                        .filter(o -> o.dimensions().isPresent())
                        .collectMap(StoredObject::storageKey, o -> o.dimensions().orElseThrow())
                        .onErrorResume(e -> {
                            log.warn("[baymax] earlier sizes unavailable documentId={} ({})", d, e.getClass().getSimpleName());
                            return Mono.just(Map.of());
                        })
                        .map(sizes -> Map.entry(d, sizes)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static BaymaxException notFound() {
        return BaymaxException.notFound("observation_not_found", "no such value on document");
    }

    /**
     * The result, then (only for DONE, the one state whose JSON carries crops) the paper's stored crop sizes, read after
     * the result has proved the paper visible to this family, so another family's probe, a retake or a failed paper
     * never costs a GET or a ledger write. A legacy crop row is measured here, on its paper's first read (D4b), tried once
     * ever and never when an image is served; pages are not measured. The storage bounds the wait
     * ({@code DocumentStorageService.READ_BUDGET}); a size read that fails sends the paper without sizes, never a 500
     * after the view was recorded.
     */
    private Mono<Map<String, Object>> finished(WebSession session, UUID documentId) {
        return results.result(session.familyId(), documentId).flatMap(r -> finishedBody(r, copy, storage));
    }

    private static Mono<Map<String, Object>> finishedBody(ResultView r, UiCopy copy, DocumentStorageUseCase storage) {
        UUID documentId = UUID.fromString(r.document().documentId());
        return sizes(r, documentId, storage).map(sizes -> PaperJson.of(r, code -> DocumentPages.retakeReasonKey(copy, code), sizes));
    }

    private static Mono<Map<String, ImageDimensions>> sizes(ResultView r, UUID documentId, DocumentStorageUseCase storage) {
        if (!r.done()) {
            return Mono.just(Map.of());
        }
        return storage.measuredObjectsOf(documentId, ObjectKind.CROP)
                .filter(o -> o.dimensions().isPresent())
                .collectMap(StoredObject::storageKey, o -> o.dimensions().orElseThrow())
                .onErrorResume(e -> {
                    log.warn("[baymax] paper sizes unavailable documentId={} ({})", documentId, e.getClass().getSimpleName());
                    return Mono.just(Map.of());
                });
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
