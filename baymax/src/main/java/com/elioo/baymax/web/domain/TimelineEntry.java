package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * One document on a patient's timeline. {@code date} is the document's own date when the page carried one;
 * otherwise the day it was received in Dhaka (V20's {@code timeline_date}), and {@code dateIsFallback} says so — the
 * UI marks it rather than presenting an upload day as a visit day.
 *
 * <p>C2: {@code urgency} is the stored F1 level (null = not yet assessed), {@code outside} the F2 count of values whose
 * stored verdict is outside or critical, {@code reviewState} the latest explanation's gate status (null = none),
 * {@code retakeReasonCode} the F5 code of a NEEDS_RETAKE paper (never the developer prose). All read from stored
 * columns by {@code TimelineReadPort}; nothing here assesses.
 */
public record TimelineEntry(UUID documentId, LocalDate date, boolean dateIsFallback, String documentType,
                            String facility, String status, int values, int medicines, int followUps,
                            int unverified, String pageOneKey, Instant createdAt,
                            Urgency urgency, int outside, String reviewState, String retakeReasonCode) {

    /** The pre-C2 shape: no stored urgency, nothing outside, no review state, no retake code. */
    public TimelineEntry(UUID documentId, LocalDate date, boolean dateIsFallback, String documentType,
                         String facility, String status, int values, int medicines, int followUps,
                         int unverified, String pageOneKey, Instant createdAt) {
        this(documentId, date, dateIsFallback, documentType, facility, status, values, medicines, followUps,
                unverified, pageOneKey, createdAt, null, 0, null, null);
    }

    /** D27: NOW and THIS_WEEK get a chip; ROUTINE and unassessed get none. The rule lives here, once; UrgencyChip calls it. */
    public Optional<Urgency> chip() {
        return urgency == null || urgency == Urgency.ROUTINE ? Optional.empty() : Optional.of(urgency);
    }
}
