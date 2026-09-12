package com.elioo.baymax.storage.adapter.out.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StoredObjectRepository extends ReactiveCrudRepository<StoredObjectEntity, UUID> {

    @Query("SELECT * FROM baymax.stored_object WHERE document_id = :documentId ORDER BY kind, page_no, item_id")
    Flux<StoredObjectEntity> findByDocumentId(UUID documentId);

    /** starts_with() rather than LIKE so that no character in the prefix is a wildcard. */
    @Modifying
    @Query("DELETE FROM baymax.stored_object WHERE starts_with(storage_key, :prefix)")
    Mono<Integer> deleteByKeyPrefix(String prefix);

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM baymax.stored_object WHERE document_id = :documentId")
    Mono<Long> bytesStored(UUID documentId);
}
