package com.elioo.baymax.aicall.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-document roll-up of {@code ai_call_log} rows in a time window.
 *
 * @param documentId    null groups the calls that were not tied to a document
 * @param unpricedCalls rows whose cost is NULL; when non-zero, {@code costUsd} is a lower bound
 * @param costUsd       SUM(cost_usd) over priced rows; null when no row was priced
 * @param models        distinct "provider/model" values, pipe-separated
 * @param avgConfidence AVG(confidence) over rows that carry one; null when none did
 */
public record DocumentAiCost(
        UUID documentId,
        long calls,
        long unpricedCalls,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        String models,
        Double avgConfidence,
        Instant firstCallAt,
        Instant lastCallAt
) {
}
