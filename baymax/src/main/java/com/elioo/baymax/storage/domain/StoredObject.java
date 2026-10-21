package com.elioo.baymax.storage.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of the object-storage ledger ({@code baymax.stored_object}): where an image lives and how big it is.
 * Never the bytes themselves.
 *
 * @param id         null until persisted
 * @param pageNo     1-based page number for {@link ObjectKind#PAGE}, else null
 * @param itemId     id of the extracted item for {@link ObjectKind#CROP}, else null
 * @param storageKey the object key in the bucket, built by {@link StorageKeys}
 * @param widthPx    the image's width in pixels, read from its header; null with {@code heightPx} when it is not known
 *                   (a row written before V24, or bytes whose header did not read), never zero
 * @param heightPx   the image's height in pixels; null exactly when {@code widthPx} is
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
        Instant createdAt,
        Integer widthPx,
        Integer heightPx
) {
    public StoredObject withId(UUID newId) {
        return new StoredObject(newId, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt, widthPx, heightPx);
    }

    /** This row with its measured size. */
    public StoredObject withDimensions(ImageDimensions size) {
        return new StoredObject(id, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt, size.width(), size.height());
    }

    /** The stored size, when both sides are known. */
    public Optional<ImageDimensions> dimensions() {
        return widthPx == null || heightPx == null ? Optional.empty() : Optional.of(new ImageDimensions(widthPx, heightPx));
    }
}
