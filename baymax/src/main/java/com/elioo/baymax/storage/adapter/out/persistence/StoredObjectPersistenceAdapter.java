package com.elioo.baymax.storage.adapter.out.persistence;

import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
import com.elioo.baymax.storage.domain.StoredObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StoredObjectPersistenceAdapter implements StoredObjectLedgerPort {

    private final StoredObjectRepository repository;

    @Override
    public Mono<StoredObject> save(StoredObject object) {
        return repository.save(StoredObjectEntity.from(object)).map(StoredObjectEntity::toRecord);
    }

    @Override
    public Flux<StoredObject> findByDocument(UUID documentId) {
        return repository.findByDocumentId(documentId).map(StoredObjectEntity::toRecord);
    }

    @Override
    public Mono<Long> deleteByKeyPrefix(String prefix) {
        return repository.deleteByKeyPrefix(prefix).map(Integer::longValue).defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> bytesStored(UUID documentId) {
        return repository.bytesStored(documentId).defaultIfEmpty(0L);
    }
}
