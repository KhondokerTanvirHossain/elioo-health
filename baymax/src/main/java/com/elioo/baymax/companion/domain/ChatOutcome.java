package com.elioo.baymax.companion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How a reserved turn ended, as {@code ChatTurnPort.complete} stores it. {@code answer} is the final assembled text the
 * family is shown, never a model's raw text. {@code violationCodes} are codes only ({@code AnswerCheck.forLog}).
 * {@code costUsd} is null when no call was priced. {@code medicines} are the prescription lines the answer quotes, each
 * with its crop (review of #166, finding 5), in the order quoted.
 */
public record ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                          int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds, List<CitedMedicine> medicines) {

    public ChatOutcome {
        violationCodes = violationCodes == null ? List.of() : List.copyOf(violationCodes);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }

    /** An outcome quoting no prescription line. */
    public ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                       int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds) {
        this(answer, kind, handler, violationCodes, modelCalls, inputTokens, outputTokens, costUsd, latencyMs, documentIds, List.of());
    }
}
