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
}
