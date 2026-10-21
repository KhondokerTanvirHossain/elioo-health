package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One card on the চ্যাট attention stack (spec §4.6, AttentionCard README), each with exactly one action. The order is
 * code urgency: NOW › THIS_WEEK › retake › the doctor's follow-up within seven days › being read. Within NOW and within
 * THIS_WEEK the read model's order: the newer document date first, then the newer upload (C2's attention query, so the
 * stack and the কাগজপত্র strip never disagree). A retake and a paper being read: the newest upload first. Follow-ups: the
 * soonest written date first.
 */
public sealed interface AttentionItem {

    UUID documentId();

    int rank();

    long sortKey();

    /** The tie-break inside one {@link #sortKey}; only the urgent cards need one (two papers of one date). */
    default long tieKey() {
        return 0;
    }

    Comparator<AttentionItem> ORDER = Comparator.comparingInt(AttentionItem::rank)
            .thenComparingLong(AttentionItem::sortKey).thenComparingLong(AttentionItem::tieKey);

    /** A finished paper whose stored F1 level is NOW or THIS_WEEK. ROUTINE has no card, no line and no chip (D27). */
    record Urgent(UUID documentId, Urgency level, String documentType, LocalDate date, boolean dateIsFallback, Instant receivedAt)
            implements AttentionItem {
        public Urgent {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(receivedAt, "receivedAt");
            if (level != Urgency.NOW && level != Urgency.THIS_WEEK) {
                throw new IllegalArgumentException("an urgent card is NOW or THIS_WEEK, never " + level);
            }
        }

        public int rank() {
            return level == Urgency.NOW ? 0 : 1;
        }

        public long sortKey() {
            return -date.toEpochDay();
        }

        @Override
        public long tieKey() {
            return -receivedAt.toEpochMilli();
        }
    }

    /**
     * A paper waiting for a new photo. {@code date} is the day it is filed under ({@code timeline_date}, read, never
     * derived here) and {@code dateIsFallback} whether that is its Dhaka upload day; {@code receivedAt} orders the stack.
     */
    record Retake(UUID documentId, LocalDate date, boolean dateIsFallback, Instant receivedAt) implements AttentionItem {
        public Retake {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        public int rank() {
            return 2;
        }

        public long sortKey() {
            return -receivedAt.toEpochMilli();
        }
    }

    /** What the doctor wrote, verbatim, with its date and the crop it was read from. */
    record FollowUp(UUID documentId, String instruction, LocalDate dueDate, String cropKey) implements AttentionItem {
        public int rank() {
            return 3;
        }

        public long sortKey() {
            return dueDate.toEpochDay();
        }
    }

    /** A paper being read, dated as {@link Retake} is. */
    record Reading(UUID documentId, LocalDate date, boolean dateIsFallback, Instant receivedAt) implements AttentionItem {
        public Reading {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        public int rank() {
            return 4;
        }

        public long sortKey() {
            return -receivedAt.toEpochMilli();
        }
    }
}
