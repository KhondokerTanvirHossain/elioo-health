package com.elioo.baymax.outbound.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A message composed for a family. {@code body} is null when the safety check failed closed. */
public record OutboundMessage(UUID id, UUID familyId, UUID patientId, UUID documentId, Kind kind, Urgency urgency,
                              List<String> urgencyReasons, String body, GateStatus gateStatus, String reviewer,
                              String rejectReason, Instant decidedAt, Instant sentAt, Instant createdAt) {

    public enum Kind {
        EXPLANATION, DETAIL, RETAKE, NUDGE, FAILURE;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromDbValue(String v) {
            return valueOf(v.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * RELEASED = not gated; deliverable. Whether it was delivered is {@code sent_at} / {@code delivery_status} (V13),
     * not this status — a DETAIL stored for the web is RELEASED and undelivered. PENDING is never delivered without
     * an explicit approve.
     */
    public enum GateStatus {
        RELEASED, PENDING, APPROVED, REJECTED, FAILED_SAFETY;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static GateStatus fromDbValue(String v) {
            return valueOf(v.toUpperCase(Locale.ROOT));
        }
    }

    public boolean isDeliverable() {
        return body != null && (gateStatus == GateStatus.RELEASED || gateStatus == GateStatus.APPROVED);
    }
}
