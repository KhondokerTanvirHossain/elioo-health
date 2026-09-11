package com.elioo.healthcare.llm.model;

/**
 * Token usage statistics from LLM invocation.
 *
 * Tracks input and output tokens consumed during model invocation.
 * Used for cost tracking and monitoring.
 *
 * @param inputTokens  Number of tokens in the input prompt
 * @param outputTokens Number of tokens in the generated response
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens
) {
    /**
     * Calculate total tokens used.
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * Calculate approximate cost based on model pricing.
     * This is a generic calculation - specific pricing should be applied by the consumer.
     *
     * @param inputCostPer1K  Cost per 1000 input tokens
     * @param outputCostPer1K Cost per 1000 output tokens
     * @return Estimated cost
     */
    public double estimateCost(double inputCostPer1K, double outputCostPer1K) {
        double inputCost = (inputTokens / 1000.0) * inputCostPer1K;
        double outputCost = (outputTokens / 1000.0) * outputCostPer1K;
        return inputCost + outputCost;
    }

    /**
     * Check if any tokens were used.
     */
    public boolean hasUsage() {
        return inputTokens > 0 || outputTokens > 0;
    }

    /**
     * Create empty usage (for cases where usage isn't tracked).
     */
    public static TokenUsage empty() {
        return new TokenUsage(0, 0);
    }
}
