package com.elioo.baymax.extraction.application.port.in;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Re-runs crop resolution for documents already extracted: stored page images are OCR'd again (Vision only,
 * metered as usual), the stored extraction_json is replayed through the current cutter, old crops and items
 * are replaced. No model call. Exists so a corpus cropped under the old, offset-only resolution (DR-12)
 * never needs re-extraction. Not scheduled; run by an operator.
 */
public interface RecropUseCase {

    record RecropReport(UUID documentId, String outcome, int values, int medicines, int followUps, int context,
                        int unverified) {
    }

    Mono<RecropReport> recrop(UUID documentId);

    /** Every DONE document, in creation order. */
    Flux<RecropReport> recropAll();
}
