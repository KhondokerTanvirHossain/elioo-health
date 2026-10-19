package com.elioo.baymax.outbound.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F1: what the document row holds. Only {@code DocumentUrgencyPort.raise} writes it, and it only rises. */
public record StoredUrgency(UUID documentId, Urgency level, List<String> reasons, Instant assessedAt) {

    public StoredUrgency {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** The stored level as the composition pipeline consumes it: compose at what is stored, never at a fresh lower read. */
    public UrgencyAssessment asAssessment() {
        return new UrgencyAssessment(level, reasons);
    }
}
