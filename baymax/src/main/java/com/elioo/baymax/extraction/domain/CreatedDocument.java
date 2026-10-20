package com.elioo.baymax.extraction.domain;

/**
 * What {@code DocumentRecordPort.createOnce} made of one press of "পাঠান" (D3, V21).
 *
 * @param document the document the key names: the new one, or the one an earlier press with the same key already made
 * @param fresh    true only for the call that inserted the row; a retry or the loser of a race gets false, and must not
 *                 store pages, take a free slot or start processing for it
 */
public record CreatedDocument(Document document, boolean fresh) {
}
