package com.elioo.baymax.storage.domain;

/**
 * Outcome of a delete-by-prefix: how many objects left the bucket and how many ledger rows went with them.
 * The two normally match; a mismatch means the ledger and the bucket had drifted and is worth a log line.
 *
 * <p>{@code rowsDeleted} is every database row a paper's delete removed, its ledger rows included
 * ({@code TimelineService.deleteDocument}); zero for a storage-only delete, which removes no other row.
 */
public record DeletionReport(String prefix, long objectsDeleted, long ledgerRowsDeleted, long rowsDeleted) {

    public DeletionReport(String prefix, long objectsDeleted, long ledgerRowsDeleted) {
        this(prefix, objectsDeleted, ledgerRowsDeleted, 0);
    }
}
