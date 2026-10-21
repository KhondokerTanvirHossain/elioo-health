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

    /** Alphabetically, then by id so two lines with one name keep one order: the health screen's ORDER BY too (plan B6). */
    @Query("SELECT * FROM baymax.medication_event WHERE document_id = :documentId ORDER BY name, id")
    Flux<MedicationEventEntity> findByDocumentId(UUID documentId);
}
