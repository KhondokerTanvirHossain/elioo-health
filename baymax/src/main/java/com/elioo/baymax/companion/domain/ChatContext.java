package com.elioo.baymax.companion.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one চ্যাট answer may be built from (DR-50 envelope 2), assembled by code from the asked patient's stored papers.
 *
 * @param factLines     the lines the model is given under FACTS, code-built, in order
 * @param numbers       the closed set: every number of a kept fact (value, printed range, date), in canonical form
 *                      ({@code AnswerCheck.canonical}); the model's own text may hold no other
 * @param verbatimLines the latest prescription's medicine lines exactly as stored; {@code {{rx:n}}} is line {@code n}
 *                      (from 1), inserted by code, never retyped by the model
 * @param documentIds   every paper a kept line came from
 * @param truncated     whether the token cap dropped a series' newest point too
 */
public record ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds,
                          boolean truncated) {

    public ChatContext {
        factLines = List.copyOf(factLines);
        numbers = Set.copyOf(numbers);
        verbatimLines = List.copyOf(verbatimLines);
        documentIds = List.copyOf(documentIds);
    }

    /** Nothing on any paper: every number is refused, and no {@code {{rx:n}}} resolves. */
    public static ChatContext empty() {
        return new ChatContext(List.of(), Set.of(), List.of(), List.of(), false);
    }

    /**
     * An over-estimate of a text's tokens, cheap and deterministic: a quarter token per ASCII character, one per other
     * character. Bangla runs well under one token per character, so the cap is conservative; the real count is logged from
     * the provider's usage.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(ascii / 4.0) + other;
    }
}
