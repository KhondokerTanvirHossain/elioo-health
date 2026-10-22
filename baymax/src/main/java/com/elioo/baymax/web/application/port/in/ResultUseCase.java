package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.web.domain.EarlierHistory;
import com.elioo.baymax.web.domain.ValueSheetView;
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

    /**
     * Plan W5-api, the চ্যাট thread's card for one paper: a {@link ReadingView} while it is RECEIVED or PROCESSING, else a
     * {@link ResultView}, read through the same visibility and the same reads as {@link #reading} and {@link #result}, and
     * <b>recording no audit row</b>: seeing a paper in the thread is not opening it (one DOCUMENT_VIEW per open, master
     * Review Focus 4). 404 when this family cannot see it.
     */
    Mono<Object> card(UUID familyId, UUID documentId);

    /**
     * B6 / plan W6-fix, one value's sheet (s13): the ONE read both sheet routes go through, the HTML page and the JSON, so
     * a rule the PO set for the sheet (ruling R1, 2026-10-22) holds on both or on neither. In this order:
     * <ol>
     *   <li>the paper, through visibility: 404 for another family; <b>404 for a paper that is not DONE</b> (NEEDS_RETAKE,
     *       FAILED, still being read), before anything else is read, so no view is recorded for it;</li>
     *   <li>the value's history ({@link EarlierHistory}): 404 when the observation is not one of THIS paper's values;
     *       the earlier readings in the same (folded) unit, same patient, finished papers before this one, newest first,
     *       at most {@code 10}, and whether the marker was also read before in another unit, so the sheet says "the
     *       first time" only when no earlier reading exists in any unit ({@link EarlierHistory#lineKey});</li>
     *   <li>the result, the one DOCUMENT_VIEW; a paper that is not DONE by then is 404 too.</li>
     * </ol>
     * There is deliberately no history read on this port without the status gate in front of it.
     */
    Mono<ValueSheetView> valueSheet(UUID familyId, UUID documentId, UUID observationId);
}
