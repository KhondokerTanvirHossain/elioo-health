package com.elioo.baymax.storage.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the object-storage ledger ({@code baymax.stored_object}): where an image lives and how big it is.
 * Never the bytes themselves.
 *
 * @param id         null until persisted
 * @param pageNo     1-based page number for {@link ObjectKind#PAGE}, else null
 * @param itemId     id of the extracted item for {@link ObjectKind#CROP}, else null
 * @param storageKey the object key in the bucket, built by {@link StorageKeys}
 */
public record StoredObject(
        UUID id,
        UUID familyId,
        UUID patientId,
        UUID documentId,
        ObjectKind kind,
        Integer pageNo,
        String itemId,
        String storageKey,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {
    public StoredObject withId(UUID newId) {
        return new StoredObject(newId, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt);
    }
}
