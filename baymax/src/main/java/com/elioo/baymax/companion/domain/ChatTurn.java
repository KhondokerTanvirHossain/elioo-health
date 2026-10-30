package com.elioo.baymax.companion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored {@code chat_turn} row (V29): what the account holder asked about one patient, and exactly what Medioo showed.
 * {@code answer}, {@code kind} and {@code answeredAt} are null together while the turn is reserved and not yet answered.
 * {@code violations} holds codes only ({@code AnswerCheck.forLog}), comma-joined, or null.
 */
public record ChatTurn(UUID id, UUID familyId, UUID patientId, String lang, String question, String answer, AnswerKind kind,
                       boolean counted, String handler, String violations, int modelCalls, int inputTokens, int outputTokens,
                       BigDecimal costUsd, Long latencyMs, List<UUID> documentIds, Instant createdAt, Instant answeredAt) {

    public ChatTurn {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
