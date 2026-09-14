package com.elioo.baymax.extraction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What survives verification: the items whose source span resolved to a crop that is now in object storage.
 * Anything that could not be proved this way was dropped upstream and never reaches persistence, so every
 * row here carries a non-null {@code cropKey}.
 *
 * @param unverified how many items were dropped, per section. A dropped item is still a real thing the
 *                   family sent, so the count is reported rather than swallowed: it tells them the page was
 *                   hard to read, and it tells us which documents to look at.
 */
public record VerifiedItems(
        List<Observation> observations,
        List<Medication> medications,
        List<FollowUpItem> followUps,
        Unverified unverified
) {
    /** Items the model reported but the server could not locate on the page, by section. */
    public record Unverified(int values, int medicines, int followUp) {

        public static Unverified none() {
            return new Unverified(0, 0, 0);
        }

        public int total() {
            return values + medicines + followUp;
        }

        public boolean any() {
            return total() > 0;
        }
    }

    public record Observation(
            UUID patientId,
            String name,
            String canonicalName,
            String value,
            String unit,
            String refLow,
            String refHigh,
            String flag,
            String cropKey,
            Instant observedAt
    ) {
    }

    public record Medication(
            UUID patientId,
            String name,
            String doseText,
            String frequencyText,
            String durationText,
            String cropKey,
            Instant at
    ) {
    }

    public record FollowUpItem(
            UUID patientId,
            String instruction,
            LocalDate dueDate,
            String cropKey
    ) {
    }

    public int total() {
        return observations.size() + medications.size() + followUps.size();
    }

    public static VerifiedItems empty() {
        return new VerifiedItems(List.of(), List.of(), List.of(), Unverified.none());
    }
}
