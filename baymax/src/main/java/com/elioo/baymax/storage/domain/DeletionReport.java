package com.elioo.baymax.storage.domain;

/**
 * Outcome of a delete-by-prefix: how many objects left the bucket and how many ledger rows went with them.
 * The two normally match; a mismatch means the ledger and the bucket had drifted and is worth a log line.
 */
public record DeletionReport(String prefix, long objectsDeleted, long ledgerRowsDeleted) {
}
