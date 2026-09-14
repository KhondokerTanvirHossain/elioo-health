package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;

/**
 * What a delete-on-request removed, returned to the caller as proof. Hard deletes, within the request.
 *
 * @param documents distinct documents that had stored images (the only document record before BMX-2)
 * @param objects   image objects removed from the bucket
 */
public record DeletionReceipt(Instant deletedAt, long families, long patients, long documents, long objects) {
}
