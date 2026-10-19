package com.elioo.baymax.extraction.application.port.out;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Read-side questions about documents that the write port ({@link DocumentRecordPort}) does not answer (B5, B6).
 * Every method reads; none writes.
 */
public interface DocumentReadPort {

    /**
     * 1, 2, 3… for a NEEDS_RETAKE document: this patient's retakes in a row up to and including it, counted from their
     * last finished paper (DONE or FAILED) sent before it. 0 for any other status, and for a document that does not exist.
     * Per patient, where WhatsApp's {@code OutboundMessagePort.consecutiveRetakes} is per family (B5 PR body).
     */
    Mono<Integer> retakeOrdinal(UUID documentId);

    /**
     * B6, the value sheet's history: this patient's observations of {@code canonicalName} on finished (DONE) papers
     * ordered before {@code beforeDocumentId} (timeline date, then sent time), newest first. The unit is NOT compared
     * here: the caller folds it (one fold for the whole module, {@code MarkerThreshold.sameUnit}). Each map holds
     * {@code id}, {@code document_id}, {@code value}, {@code unit} (absent when none was printed), {@code crop_key},
     * {@code date} (the paper's {@code timeline_date}) and {@code date_is_fallback} ({@code doc_date IS NULL}).
     */
    Flux<Map<String, Object>> readingsOf(UUID patientId, String canonicalName, UUID beforeDocumentId);
}
