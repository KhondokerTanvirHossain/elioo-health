package com.elioo.baymax.companion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How a reserved turn ended, as {@code ChatTurnPort.complete} stores it. {@code answer} is the final assembled text the
 * family is shown, never a model's raw text. {@code violationCodes} are codes only ({@code AnswerCheck.forLog}).
 * {@code costUsd} is null when no call was priced. {@code medicines} are the prescription lines the answer quotes, each
 * with its crop (review of #166, finding 5), in the order quoted. {@code modelCalls} counts the chat model's calls only (an
 * answer and its one repair); {@code checkCalls} the refuse-only model check's (PO ruling P15, the {@code chat_model_check}
 * migration); the tokens and the cost are every call of the turn, the check's included.
 */
public record ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                          int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds, List<CitedMedicine> medicines,
                          int checkCalls) {

    public ChatOutcome {
        violationCodes = violationCodes == null ? List.of() : List.copyOf(violationCodes);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }

    /** An outcome quoting the given lines, which no model check read. */
    public ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                       int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds, List<CitedMedicine> medicines) {
        this(answer, kind, handler, violationCodes, modelCalls, inputTokens, outputTokens, costUsd, latencyMs, documentIds, medicines, 0);
    }

    /** An outcome quoting no prescription line, after {@code checkCalls} model checks. */
    public ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                       int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds, int checkCalls) {
        this(answer, kind, handler, violationCodes, modelCalls, inputTokens, outputTokens, costUsd, latencyMs, documentIds, List.of(),
                checkCalls);
    }

    /** An outcome quoting no prescription line, which no model check read. */
    public ChatOutcome(String answer, AnswerKind kind, String handler, List<String> violationCodes, int modelCalls, int inputTokens,
                       int outputTokens, BigDecimal costUsd, long latencyMs, List<UUID> documentIds) {
        this(answer, kind, handler, violationCodes, modelCalls, inputTokens, outputTokens, costUsd, latencyMs, documentIds, List.of(), 0);
    }
}
