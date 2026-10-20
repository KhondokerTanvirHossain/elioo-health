package com.elioo.baymax.extraction.application.port.in;

import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Taking a document in, and reporting what became of it. */
public interface DocumentIntakeUseCase {

    /**
     * Checks the free tier, renders and stores the pages, records the document, and starts processing.
     * Returns as soon as the document exists, so the caller gets 202 rather than waiting for the models.
     */
    Mono<Document> accept(UUID patientId, List<Upload> uploads);

    /**
     * D3: {@link #accept(UUID, List)} for one press of "পাঠান" on the web, all files one document. A retry with the same
     * key returns the document the first press made and renders, stores, counts and processes nothing; a key already
     * used for another patient is 409 {@code upload_key_reused}. A null key is the two-argument form.
     */
    Mono<Document> accept(UUID patientId, List<Upload> uploads, String uploadKey);

    /**
     * D3 "আলাদা আলাদা": one document per file, in order, file {@code i} under the key {@code uploadKey:i}. All or nothing
     * before any document exists: every file not already made by an earlier press with the key is read, and the
     * allowance is asked for all of them at once; one unreadable file or too few slots refuses the lot.
     */
    Flux<Document> acceptEach(UUID patientId, List<Upload> uploads, String uploadKey);

    /** Status, plus the extracted items once the document is DONE. */
    Mono<DocumentView> view(UUID documentId);
}
