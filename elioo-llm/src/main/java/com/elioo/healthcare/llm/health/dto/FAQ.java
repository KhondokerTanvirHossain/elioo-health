package com.elioo.healthcare.llm.health.dto;

/**
 * Represents a frequently asked question with answer.
 *
 * @param question    The question
 * @param answer      The answer
 */
public record FAQ(
        String question,
        String answer
) {
    /**
     * Check if FAQ has content.
     */
    public boolean hasContent() {
        return question != null && !question.isBlank() &&
                answer != null && !answer.isBlank();
    }
}
