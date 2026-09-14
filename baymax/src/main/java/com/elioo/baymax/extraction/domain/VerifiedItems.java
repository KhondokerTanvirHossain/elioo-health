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
        List<ContextLine> clinicalContext,
        Unverified unverified
) {
    /**
     * One verified line of the clinical narrative. Kept as a flat list with its section so it can be
     * stored as a single JSONB column: these lines are read together, never queried field by field.
     *
     * @param section chief_complaint | history | examination | diagnosis | investigations_advised |
     *                advice | referral
     */
    public record ContextLine(String section, String text, String duration, String cropKey) {
    }
    /** Items the model reported but the server could not locate on the page, by section. */
    public record Unverified(int values, int medicines, int followUp, int clinicalContext) {

        /** Backwards-compatible: no clinical-context drops. */
        public Unverified(int values, int medicines, int followUp) {
            this(values, medicines, followUp, 0);
        }

        public static Unverified none() {
            return new Unverified(0, 0, 0, 0);
        }

        public int total() {
            return values + medicines + followUp + clinicalContext;
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
            String route,
            String frequencyText,
            String timingText,
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
        return observations.size() + medications.size() + followUps.size() + clinicalContext.size();
    }

    public static VerifiedItems empty() {
        return new VerifiedItems(List.of(), List.of(), List.of(), List.of(), Unverified.none());
    }
}
