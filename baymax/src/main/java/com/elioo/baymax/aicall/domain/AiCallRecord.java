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
        Instant createdAt
) {
    public AiCallRecord withId(UUID newId) {
        return new AiCallRecord(newId, documentId, purpose, provider, model, inputTokens, outputTokens,
                costUsd, latencyMs, confidence, createdAt);
    }
}
