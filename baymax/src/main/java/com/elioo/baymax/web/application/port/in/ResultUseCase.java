package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** One document as its page reads it; 404 when this family cannot see it (never 403: ids cannot be probed). */
public interface ResultUseCase {

    /** The status alone, for the reading page's poll. Records no audit event (it is called every 5 s). */
    Mono<Document.Status> status(UUID familyId, UUID documentId);

    /** The reading view (records DOCUMENT_VIEW, as the document page always has). */
    Mono<ReadingView> reading(UUID familyId, UUID documentId);

    /**
     * One document as its result page shows it (records DOCUMENT_VIEW): the stored F1 level from DONE onwards, and the
     * newest explanation in whatever review state it is in (F3). 404 when this family cannot see it.
     */
    Mono<ResultView> result(UUID familyId, UUID documentId);
}
