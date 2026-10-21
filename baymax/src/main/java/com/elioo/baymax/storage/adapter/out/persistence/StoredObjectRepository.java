package com.elioo.baymax.storage.adapter.out.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Only a row never tried takes a size: a stored size is never rewritten, and two first reads racing record it once.
     * Every multi-parameter {@code @Query} here binds with {@code @Param}: without it Spring Data R2DBC binds by position.
     */
    @Modifying
    @Query("UPDATE baymax.stored_object SET width_px = :width, height_px = :height, dimensions_checked_at = now() "
            + "WHERE storage_key = :storageKey AND dimensions_checked_at IS NULL")
    Mono<Integer> recordDimensions(@Param("storageKey") String storageKey, @Param("width") int width, @Param("height") int height);

    /** A row never tried, found unreadable: it keeps no size and is never fetched to measure again. */
    @Modifying
    @Query("UPDATE baymax.stored_object SET dimensions_checked_at = now() WHERE storage_key = :storageKey AND dimensions_checked_at IS NULL")
    Mono<Integer> recordUnmeasurable(@Param("storageKey") String storageKey);
}
