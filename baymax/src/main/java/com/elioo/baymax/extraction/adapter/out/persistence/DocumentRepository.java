package com.elioo.baymax.extraction.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface DocumentRepository extends ReactiveCrudRepository<DocumentEntity, UUID> {

    /** BMX-5 timeline page: newest first, strictly before the cursor. Indexed by document_patient_idx. */
    @org.springframework.data.r2dbc.repository.Query(
            "SELECT * FROM baymax.document WHERE patient_id = :patientId AND created_at < :before ORDER BY created_at DESC LIMIT :limit")
    reactor.core.publisher.Flux<DocumentEntity> timelineOf(java.util.UUID patientId, java.time.OffsetDateTime before, int limit);

    /** BMX-10: the family's newest readable document, for answering a "বিস্তারিত" reply. */
    @org.springframework.data.r2dbc.repository.Query(
            "SELECT * FROM baymax.document WHERE family_id = :familyId AND status = 'DONE' ORDER BY created_at DESC LIMIT 1")
    reactor.core.publisher.Mono<DocumentEntity> latestDone(java.util.UUID familyId);
}
