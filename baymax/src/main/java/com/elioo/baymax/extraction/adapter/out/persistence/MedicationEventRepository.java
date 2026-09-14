package com.elioo.baymax.extraction.adapter.out.persistence;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Read access to the medicines of a document. Writes go through {@link PostgresDocumentRecordAdapter},
 * which inserts them in the same transaction as the document.
 */
public interface MedicationEventRepository extends ReactiveCrudRepository<MedicationEventEntity, UUID> {

    @Query("SELECT * FROM baymax.medication_event WHERE document_id = :documentId ORDER BY name")
    Flux<MedicationEventEntity> findByDocumentId(UUID documentId);
}
