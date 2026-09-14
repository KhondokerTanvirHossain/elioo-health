package com.elioo.baymax.extraction.application.port.in;

import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
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

    /** Status, plus the extracted items once the document is DONE. */
    Mono<DocumentView> view(UUID documentId);
}
