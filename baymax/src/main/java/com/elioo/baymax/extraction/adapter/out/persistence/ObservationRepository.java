package com.elioo.baymax.extraction.adapter.out.persistence;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Read access to the observations of a document. Writes go through
 * {@link PostgresDocumentRecordAdapter}, which inserts them in the same transaction as the document.
 */
public interface ObservationRepository extends ReactiveCrudRepository<ObservationEntity, UUID> {

    @Query("SELECT * FROM baymax.observation WHERE document_id = :documentId ORDER BY observed_at, name")
    Flux<ObservationEntity> findByDocumentId(UUID documentId);
}
