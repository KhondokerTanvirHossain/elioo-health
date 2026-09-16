package com.elioo.baymax.extraction.application.port.out;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/**
 * The document side of the health record (DR-1): documents and the verified items extracted from them.
 * Separate interface from {@code HealthRecordPort} only to keep each one readable; the same Postgres
 * adapter package implements both, and no service touches a repository.
 */
public interface DocumentRecordPort {

    Mono<Document> create(Document document);

    Mono<Document> find(UUID documentId);

    Mono<Document> update(Document document);

    /**
     * Writes the extraction outcome and its items in one transaction: the document row, then every
     * observation, medication event and follow-up. Items arrive already verified, each with its crop key.
     */
    Mono<Document> saveExtraction(Document document, VerifiedItems items);

    /** Recomputes {@code document.cost_usd} from this document's {@code ai_call_log} rows. */
    Mono<BigDecimal> refreshCost(UUID documentId);

    // --- reading the verified items back, for the document view --------------------------------

    /** Each value with its unit, range, flag and the key of the crop that proves it. */
    Flux<Map<String, Object>> observationsOf(UUID documentId);

    Flux<Map<String, Object>> medicinesOf(UUID documentId);

    Flux<Map<String, Object>> followUpsOf(UUID documentId);

    /**
     * The verified clinical narrative, grouped by section, as stored on the document. Persisted since V7
     * but unreachable until now, which made every clinical_context score read as zero.
     */
    Mono<Map<String, Object>> clinicalContextOf(UUID documentId);

    // --- counts used by the free tier and the weekly export -------------------------------------

    /** Documents the family created in the calendar month, excluding ones that never got past intake. */
    Mono<Long> countInMonth(UUID familyId, YearMonth month);

    Mono<Long> countInWindow(UUID familyId, Instant from, Instant to);

    Mono<Instant> lastDocumentAt(UUID familyId);

    Flux<UUID> idsOfFamily(UUID familyId);

    Flux<UUID> idsOfPatient(UUID patientId);
}
