package com.elioo.healthcare.llm.model;

import java.util.Map;

/**
 * Generic LLM invocation response.
 *
 * This model represents a domain-agnostic response from a Large Language Model
 * from any provider. It normalizes responses from different model providers.
 *
 * @param content      The generated text content
 * @param stopReason   Reason for stopping generation (e.g., "end_turn", "max_tokens")
 * @param usage        Token usage statistics
 * @param modelId      Model that generated the response
 * @param metadata     Additional response metadata
 */
public record LlmResponse(
        String content,
        String stopReason,
        TokenUsage usage,
        String modelId,
        Map<String, Object> metadata
) {
    /**
     * Create a simple response with just content.
     */
    public static LlmResponse simple(String content) {
        return new LlmResponse(content, null, null, null, null);
    }

    /**
     * Create a response with content and usage stats.
     */
    public static LlmResponse withUsage(String content, TokenUsage usage) {
        return new LlmResponse(content, null, usage, null, null);
    }

    /**
     * Check if content is present.
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /**
     * Check if usage statistics are available.
     */
    public boolean hasUsage() {
        return usage != null;
    }

    /**
     * Get content length.
     */
    public int getContentLength() {
        return content != null ? content.length() : 0;
    }

    /**
     * Check if generation was stopped due to max tokens.
     */
    public boolean isMaxTokensReached() {
        return "max_tokens".equals(stopReason) || "length".equals(stopReason);
    }

    /**
     * Check if generation completed naturally.
     */
    public boolean isComplete() {
        return "end_turn".equals(stopReason) || "stop_sequence".equals(stopReason);
    }

    /**
     * Get total tokens used (if available).
     */
    public int getTotalTokens() {
        return hasUsage() ? usage.totalTokens() : 0;
    }
}
