package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import reactor.core.publisher.Flux;
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

    /**
     * Plan W5-api, the চ্যাট thread's card for one paper: a {@link ReadingView} while it is RECEIVED or PROCESSING, else a
     * {@link ResultView}, read through the same visibility and the same reads as {@link #reading} and {@link #result}, and
     * <b>recording no audit row</b>: seeing a paper in the thread is not opening it (one DOCUMENT_VIEW per open, master
     * Review Focus 4). 404 when this family cannot see it.
     */
    Mono<Object> card(UUID familyId, UUID documentId);

    /**
     * B6, the value sheet (s13): the earlier readings of one value on this document — the same marker and the same unit
     * (folded, so MICRO SIGN and GREEK MU are one), the same patient, from finished papers before this one, newest first,
     * at most {@code 10}. 404 when this family cannot see the document, and when the observation is not one of THIS
     * document's values (an id from another paper never opens a sheet here). Records nothing.
     */
    Flux<EarlierReading> earlierReadings(UUID familyId, UUID documentId, UUID observationId);
}
