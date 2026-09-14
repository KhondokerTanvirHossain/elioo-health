package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-family counters for the weekly pilot log.
 *
 * @param documentsInWindow documents the family created in the export window
 * @param lastDocumentAt    when the family last sent a document, any time; null when never
 */
public record FamilyActivity(
        UUID familyId,
        FamilyAccount.Plan plan,
        long patients,
        long documentsInWindow,
        Instant lastDocumentAt
) {
}
