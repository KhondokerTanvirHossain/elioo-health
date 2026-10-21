package com.elioo.baymax.storage.application.port.out;

import com.elioo.baymax.storage.domain.StoredObject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The {@code baymax.stored_object} ledger: which keys exist for which document, and how many bytes they hold. */
public interface StoredObjectLedgerPort {

    Mono<StoredObject> save(StoredObject object);

    Flux<StoredObject> findByDocument(UUID documentId);

    /** Removes the rows whose key starts with the prefix; returns how many. */
    Mono<Long> deleteByKeyPrefix(String prefix);

    /** SUM(size_bytes) for the document; 0 when it has no objects. */
    Mono<Long> bytesStored(UUID documentId);

    /**
     * Records a measured size on the row with this key, only if its size was never tried: a stored size is never
     * rewritten. Returns how many rows took it (0 when the row was already tried, or is gone).
     */
    Mono<Long> recordDimensions(String storageKey, int width, int height);

    /**
     * Records that the row with this key was tried and has no readable size (its object is missing, does not decode, or
     * is over the upload cap), only if it was never tried: it is never fetched to measure again. Returns how many rows
     * took it.
     */
    Mono<Long> recordUnmeasurable(String storageKey);
}
