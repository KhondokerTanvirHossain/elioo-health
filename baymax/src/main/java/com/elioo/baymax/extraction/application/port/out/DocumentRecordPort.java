package com.elioo.baymax.extraction.application.port.out;

import com.elioo.baymax.extraction.domain.CreatedDocument;
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

    /**
     * D3 (V21): creates the document for one press of "পাঠান", at most once per family and key. A retry, a double tap
     * or the loser of a race with the same key gets the row the first call made, with {@code fresh = false}; the
     * database's partial unique index {@code document_upload_key_uq} decides, not a read before the write. A null key is
     * {@link #create} with {@code fresh = true}. The only writer of {@code upload_key}.
     */
    Mono<CreatedDocument> createOnce(Document document, String uploadKey);

    /** The family's document made by the press with this key; empty when none. */
    Mono<Document> findByUploadKey(UUID familyId, String uploadKey);

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

    /** The family's newest readable document, for answering a "বিস্তারিত" reply about the one just explained. */
    Mono<Document> latestDoneDocument(UUID familyId);

    Flux<UUID> idsOfFamily(UUID familyId);

    Flux<UUID> idsOfPatient(UUID patientId);

    /**
     * A patient's documents newest first, strictly older than {@code before} (by created_at), at most
     * {@code limit}. The timeline pages on created_at because doc_date may be absent (BMX-5).
     */
    Flux<Document> timelineOf(UUID patientId, Instant before, int limit);

    /**
     * A document if the family may see it: it belongs to one of the family's own patients, or to a patient
     * the family is a share member on. Decided in SQL so a service cannot get it wrong; empty means 404.
     */
    Mono<Document> findVisible(UUID familyId, UUID documentId);

    /** Stored (crop-verified) item counts: values, medicines, follow-ups. */
    Mono<int[]> sectionCounts(UUID documentId);

    /**
     * Hard-deletes one document's rows, in ONE transaction, the document last. With it go its items (observations,
     * medicines, follow-ups), its {@code stored_object} ledger rows (the bucket objects stay, for the storage module to
     * remove once this has committed), and everything about it that has not reached the family: every unsent
     * {@code outbound_message} naming it, every nudge that quotes it and was not delivered — by trigger key, and the
     * cross-paper trend and medicine_changed nudges that quote its readings or medicines — and the unsent message of a
     * gated one. Kept, pending PO decision P1: delivered history (a message with {@code sent_at}, a {@code sent} nudge,
     * a gated nudge whose message was accepted) and the audit. ai_call_log rows keep their numbers and lose the
     * document_id, as for family deletion (BMX-4); the WhatsApp message it arrived in loses its media id (V22). After
     * this, a message naming the document can no longer be stored ({@code OutboundMessagePort.save}).
     *
     * @return every row deleted, across all tables (not 0 or 1)
     */
    Mono<Long> deleteDocument(UUID documentId);

    /** Removes a document's stored items (observations, medicines, follow-ups) but keeps the document; for re-cropping. */
    Mono<Long> deleteItems(UUID documentId);

    /** Ids of every document in a status, oldest first. */
    Flux<UUID> idsWithStatus(Document.Status status);

    /**
     * F9b: increments and commits {@code recovery_attempts}, returning the new count. Empty when the document is no
     * longer RECEIVED/PROCESSING or was updated at or after {@code interruptedBefore} (the subscription that is
     * running it belongs to this instance). This counts the attempt; it is not a claim: neither {@code status} nor
     * {@code updated_at} changes, so two sweeps with similar cutoffs would both get a row. Only one sweep runs because
     * deploy.sh runs one container (fixed name, fixed host port).
     */
    Mono<Integer> markRecoveryAttempt(UUID documentId, Instant interruptedBefore);

    /**
     * F9b: DONE or NEEDS_RETAKE documents updated in {@code [since, before)} that have no explanation or retake
     * message with a body (a FAILED_SAFETY row alone is no answer, PR 9), and FAILED ones with no failure notice
     * (PR 9) — the process died between extraction and {@code explain()}. Oldest first.
     */
    Flux<UUID> unexplainedSince(Instant since, Instant before);

    /**
     * How many DONE or NEEDS_RETAKE documents updated before {@code since} were never explained; counted, never
     * messaged. FAILED ones are counted apart ({@link #countFailedUnnotifiedBefore}): every FAILED document from before
     * V23 has no notice and never will, and mixed in here they would hide the one signal this count exists for.
     */
    Mono<Long> countUnexplainedBefore(Instant since);

    /** How many FAILED documents updated before {@code since} have no failure notice (PR 9); counted, never messaged. */
    Mono<Long> countFailedUnnotifiedBefore(Instant since);
}
