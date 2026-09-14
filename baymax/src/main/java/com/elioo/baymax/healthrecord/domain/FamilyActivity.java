package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-family counters for the weekly pilot log.
 *
 * @param documentsInWindow distinct documents with stored images created in the export window
 * @param lastDocumentAt    most recent stored image for the family, any time; null when none
 */
public record FamilyActivity(
        UUID familyId,
        FamilyAccount.Plan plan,
        long patients,
        long documentsInWindow,
        Instant lastDocumentAt
) {
}
