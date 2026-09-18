package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.TimelinePage;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Everything a session may do, always scoped by the family it belongs to. A thing the family may not see
 * is a 404 — the same answer as for a thing that does not exist, so ids cannot be probed. A thing it may
 * see but not change is a 403.
 */
public interface TimelineUseCase {

    Mono<FamilyOverview> me(UUID familyId);

    Mono<TimelinePage> timeline(UUID familyId, UUID patientId, String cursor);

    Mono<DocumentView> document(UUID familyId, UUID documentId);

    /** Owner only; a share member gets 403. */
    Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads);

    /** Owner only; a share member gets 403. */
    Mono<DeletionReport> deleteDocument(UUID familyId, UUID documentId);

    /**
     * A fresh signed URL for a page or crop of a document the family may see. Signed at call time, so an
     * {@code <img>} that points at the endpoint serving this never shows an expired link.
     */
    Mono<URI> imageUrl(UUID familyId, UUID documentId, String storageKey);

    /** Signed URL for page {@code n} of a document the family may see; the key is derived, never taken from the client. */
    Mono<URI> pageUrl(UUID familyId, UUID documentId, int pageNo);

    /** The newest released or approved message for a document the family may see; empty when none or pending. */
    Mono<String> explanationOf(UUID familyId, UUID documentId);
}
