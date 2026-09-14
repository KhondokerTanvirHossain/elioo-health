package com.elioo.baymax.extraction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An uploaded document: its pages live in object storage, its extraction lives here.
 *
 * @param statusReason why a document needs a retake or failed; developer-facing, never patient text
 * @param modelFinal   provider/model whose result was kept, once extraction has run
 * @param costUsd      the sum of this document's {@code ai_call_log} rows
 */
public record Document(
        UUID id,
        UUID patientId,
        UUID familyId,
        String documentType,
        LocalDate docDate,
        String facility,
        String extractionJson,
        Double confidenceOverall,
        Status status,
        String statusReason,
        String modelFinal,
        BigDecimal costUsd,
        int pageCount,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        RECEIVED, PROCESSING, DONE, NEEDS_RETAKE, FAILED;

        public static Status fromDbValue(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** A freshly accepted upload, before any processing. */
    public static Document received(UUID patientId, UUID familyId, int pageCount, Instant now) {
        return new Document(null, patientId, familyId, null, null, null, null, null,
                Status.RECEIVED, null, null, null, pageCount, now, now);
    }

    public Document withId(UUID newId) {
        return new Document(newId, patientId, familyId, documentType, docDate, facility, extractionJson,
                confidenceOverall, status, statusReason, modelFinal, costUsd, pageCount, createdAt, updatedAt);
    }

    public Document withStatus(Status newStatus, String reason, Instant at) {
        return new Document(id, patientId, familyId, documentType, docDate, facility, extractionJson,
                confidenceOverall, newStatus, reason, modelFinal, costUsd, pageCount, createdAt, at);
    }
}
