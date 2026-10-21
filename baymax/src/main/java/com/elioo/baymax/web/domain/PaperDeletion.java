package com.elioo.baymax.web.domain;

/**
 * What deleting one paper removed ({@code TimelineUseCase.deleteDocument}): the objects under its prefix, the ledger
 * rows that recorded them (counted before they went with the paper's rows), and every database row the delete
 * removed, the ledger rows included. {@code objectsDeleted} is 0 when the bucket could not be emptied after the rows
 * had gone (logged as {@code orphaned_objects}).
 */
public record PaperDeletion(String prefix, long objectsDeleted, long ledgerRowsDeleted, long rowsDeleted) {
}
