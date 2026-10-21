package com.elioo.baymax.storage.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of the object-storage ledger ({@code baymax.stored_object}): where an image lives and how big it is.
 * Never the bytes themselves.
 *
 * @param id                  null until persisted
 * @param pageNo              1-based page number for {@link ObjectKind#PAGE}, else null
 * @param itemId              id of the extracted item for {@link ObjectKind#CROP}, else null
 * @param storageKey          the object key in the bucket, built by {@link StorageKeys}
 * @param widthPx             the image's width in pixels, read from its header; null with {@code heightPx} when it is not
 *                            known (a row written before the dimension columns existed, or an object whose header did not
 *                            read), never zero
 * @param heightPx            the image's height in pixels; null exactly when {@code widthPx} is
 * @param dimensionsCheckedAt when the size was read, or found unreadable (object missing, bytes that do not decode, over the
 *                            upload cap); null only for a row whose size was never tried, which is the one row a paper's
 *                            read measures. A row with a size always has one (the database's CHECK)
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
        Integer heightPx,
        Instant dimensionsCheckedAt
) {
    /**
     * A row whose size, if it has one, was read when it was created: {@code dimensionsCheckedAt} is {@code createdAt} for a
     * sized row and null (never tried) for an unsized one. For callers that build a row by hand (tests); the store and the
     * ledger use the canonical constructor and say when the size was tried.
     */
    public StoredObject(UUID id, UUID familyId, UUID patientId, UUID documentId, ObjectKind kind, Integer pageNo, String itemId,
                        String storageKey, String contentType, long sizeBytes, Instant createdAt, Integer widthPx, Integer heightPx) {
        this(id, familyId, patientId, documentId, kind, pageNo, itemId, storageKey, contentType, sizeBytes, createdAt, widthPx,
                heightPx, widthPx == null ? null : createdAt);
    }

    public StoredObject withId(UUID newId) {
        return new StoredObject(newId, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt, widthPx, heightPx, dimensionsCheckedAt);
    }

    /** This row with its measured size, read at {@code checkedAt}. */
    public StoredObject withDimensions(ImageDimensions size, Instant checkedAt) {
        return new StoredObject(id, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt, size.width(), size.height(), checkedAt);
    }

    /** This row found unmeasurable at {@code checkedAt}: still without a size, and never fetched to measure again. */
    public StoredObject withoutDimensions(Instant checkedAt) {
        return new StoredObject(id, familyId, patientId, documentId, kind, pageNo, itemId, storageKey,
                contentType, sizeBytes, createdAt, null, null, checkedAt);
    }

    /** The stored size, when both sides are known. */
    public Optional<ImageDimensions> dimensions() {
        return widthPx == null || heightPx == null ? Optional.empty() : Optional.of(new ImageDimensions(widthPx, heightPx));
    }

    /** True only for a row whose size was never tried: the one row a paper's read fetches and measures. */
    public boolean neverMeasured() {
        return dimensionsCheckedAt == null;
    }
}
