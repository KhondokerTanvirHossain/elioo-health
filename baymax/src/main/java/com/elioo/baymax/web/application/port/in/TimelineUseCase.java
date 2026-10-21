package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.web.domain.CalendarMonth;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PaperDeletion;
import com.elioo.baymax.web.domain.TimelineFilter;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Everything a session may do, always scoped by the family it belongs to. A thing the family may not see
 * is a 404 — the same answer as for a thing that does not exist, so ids cannot be probed. A thing it may
 * see but not change is a 403.
 */
public interface TimelineUseCase {

    Mono<FamilyOverview> me(UUID familyId);

    /**
     * One page of a patient's papers, newest document date first (C2's read model). {@code cursor} is a previous page's
     * {@code nextCursor} (opaque); null, blank or a pre-C2 instant opens the first page; anything else is 400.
     * {@link TimelineFilter#ALL} is the unfiltered list. One signature only: a second overload would let a mock stub or
     * a {@code verify(never())} name the form no caller uses and pass for that reason.
     */
    Mono<TimelinePage> timeline(UUID familyId, UUID patientId, String cursor, TimelineFilter filter);

    /**
     * Plan B3: one month of a patient's papers (by their filing day) and the open follow-ups due inside it.
     * {@code month} null is the patient's current month. Records no audit row (PO decision S14): the papers page reads
     * {@link #timeline} and this calendar for one render, and the timeline read records one TIMELINE_VIEW each time it is
     * read. A month, filter or "more" link is a new render of the page, so it still records one view, through the timeline.
     * A patient this family cannot see is a 404.
     */
    Mono<CalendarMonth> calendar(UUID familyId, UUID patientId, YearMonth month);

    Mono<DocumentView> document(UUID familyId, UUID documentId);

    /**
     * The paper, when this family may delete it: 404 {@code document_not_found} when it cannot see it (the same as one
     * that does not exist), 403 {@code shared_patient_read_only} when it sees it as a share member. The one ownership
     * rule every delete path asks — {@link #deleteDocument} itself and the {@code /app/api} confirm-token route.
     */
    Mono<Document> deletableDocument(UUID familyId, UUID documentId);

    /** Owner only ({@link #deletableDocument}); rows first and committed, then the bucket. */
    Mono<PaperDeletion> deleteDocument(UUID familyId, UUID documentId);

    /**
     * A fresh signed URL for a page or crop of a document the family may see. Signed at call time, so an
     * {@code <img>} that points at the endpoint serving this never shows an expired link.
     */
    Mono<URI> imageUrl(UUID familyId, UUID documentId, String storageKey);

    /** Signed URL for page {@code n} of a document the family may see; the key is derived, never taken from the client. */
    Mono<URI> pageUrl(UUID familyId, UUID documentId, int pageNo);

    /** BMX-8: the family ticks the patient's chronic flags; owner only; never set from extracted data. */
    Mono<PatientProfile> updateChronicFlags(UUID familyId, UUID patientId, List<String> chronicFlags);
}
