package com.elioo.baymax.outbound.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F1: what the document row holds. Only {@code DocumentUrgencyPort.raise} writes it, and it only rises.
 *
 * <p>F2: {@code values} are the document's stored value verdicts (V19), one per observation that has one; each only
 * rises too. Read back with the level, so whatever composes at the stored level reads the stored rows as well.
 */
public record StoredUrgency(UUID documentId, Urgency level, List<String> reasons, Instant assessedAt, List<ValueVerdict> values) {

    public StoredUrgency {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** A stored level with no value verdicts. */
    public StoredUrgency(UUID documentId, Urgency level, List<String> reasons, Instant assessedAt) {
        this(documentId, level, reasons, assessedAt, List.of());
    }

    /** The stored level as the composition pipeline consumes it: compose at what is stored, never at a fresh lower read. */
    public UrgencyAssessment asAssessment() {
        return new UrgencyAssessment(level, reasons, values);
    }
}
