package com.elioo.baymax.aicall.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One LLM or Vision call, as written to {@code baymax.ai_call_log}. Numbers and ids only: this record
 * must never carry prompt text, model output, OCR text or anything else that could identify a patient.
 *
 * @param id           null until persisted
 * @param documentId   the Baymax document the call served, or null for calls not tied to one
 * @param costUsd      computed from the configured price table; null when no price is configured
 * @param latencyMs    wall-clock time of the provider call
 * @param confidence   0..1 when the call yields one (OCR average, extraction "overall"), else null
 * @param status       OK when the provider answered; FAILED for a call that errored (zero tokens, null cost)
 */
public record AiCallRecord(
        UUID id,
        UUID documentId,
        AiCallPurpose purpose,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        Long latencyMs,
        Double confidence,
        Instant createdAt,
        Status status
) {
    /** Outcome of the provider call. */
    public enum Status {
        OK, FAILED;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Status fromDbValue(String value) {
            return value == null ? OK : valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Backwards-compatible constructor: a successful call. */
    public AiCallRecord(UUID id, UUID documentId, AiCallPurpose purpose, String provider, String model,
                        int inputTokens, int outputTokens, java.math.BigDecimal costUsd, Long latencyMs,
                        Double confidence, Instant createdAt) {
        this(id, documentId, purpose, provider, model, inputTokens, outputTokens, costUsd, latencyMs,
                confidence, createdAt, Status.OK);
    }

    /** A call that errored: no tokens, no cost, but the latency actually spent waiting. */
    public static AiCallRecord failed(UUID documentId, AiCallPurpose purpose, String provider, String model,
                                      long latencyMs, Instant at) {
        return new AiCallRecord(null, documentId, purpose, provider, model, 0, 0, null, latencyMs, null,
                at, Status.FAILED);
    }

    public AiCallRecord withId(UUID newId) {
        return new AiCallRecord(newId, documentId, purpose, provider, model, inputTokens, outputTokens,
                costUsd, latencyMs, confidence, createdAt, status);
    }
}
