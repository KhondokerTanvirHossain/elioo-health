package com.elioo.baymax.companion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How a reserved turn ended, as {@code ChatTurnPort.complete} stores it. {@code answer} is the final assembled text the
 * family is shown, never a model's raw text. {@code violationCodes} are codes only ({@code AnswerCheck.forLog}).
 * {@code costUsd} is null when no call was priced.
 */
public record ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                          int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds) {

    public ChatOutcome {
        violationCodes = violationCodes == null ? List.of() : List.copyOf(violationCodes);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
