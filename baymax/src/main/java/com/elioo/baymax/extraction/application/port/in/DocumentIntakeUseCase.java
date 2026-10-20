package com.elioo.baymax.extraction.application.port.in;

import com.elioo.baymax.extraction.domain.CreatedDocument;
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
     * key and the same files returns the document the first press made and renders, stores, counts and processes
     * nothing; other files under the same key (a sheet restored by Back, another paper chosen) are a new press. The same
     * press already made for another patient is 409 {@code upload_key_reused}. A null key is the two-argument form. {@code fresh} says
     * whether this call made the document, so a replay is never recorded as an upload.
     */
    Mono<CreatedDocument> accept(UUID patientId, List<Upload> uploads, String uploadKey);

    /**
     * D3 "আলাদা আলাদা": one document per file, in order, file {@code i} under the press's key (the key and
     * the files) and {@code :i}. All or nothing
     * before any document exists: every file not already made by an earlier press with the key is read, and the
     * allowance is asked for all of them at once; one unreadable file or too few slots refuses the lot.
     */
    Flux<CreatedDocument> acceptEach(UUID patientId, List<Upload> uploads, String uploadKey);

    /** Status, plus the extracted items once the document is DONE. */
    Mono<DocumentView> view(UUID documentId);
}
