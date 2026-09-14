package com.elioo.baymax.extraction.adapter.out.persistence;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Read access to the follow-ups of a document. Writes go through {@link PostgresDocumentRecordAdapter},
 * which inserts them in the same transaction as the document.
 */
public interface FollowUpRepository extends ReactiveCrudRepository<FollowUpEntity, UUID> {

    @Query("SELECT * FROM baymax.follow_up WHERE document_id = :documentId ORDER BY due_date NULLS LAST")
    Flux<FollowUpEntity> findByDocumentId(UUID documentId);
}
