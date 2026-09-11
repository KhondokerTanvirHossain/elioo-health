package com.elioo.healthcare.aws.bedrock.model;

import java.util.List;
import java.util.Map;

/**
 * Generic LLM invocation request.
 *
 * This model represents a domain-agnostic request to invoke a Large Language Model
 * via AWS Bedrock. It supports various model providers (Claude, Titan, etc.).
 *
 * @param userPrompt   The user's prompt/question
 * @param systemPrompt System instructions for the model (optional)
 * @param modelId      AWS Bedrock model identifier
 * @param maxTokens    Maximum tokens to generate
 * @param temperature  Randomness/creativity (0.0-1.0)
 * @param topP         Nucleus sampling parameter (optional)
 * @param stopSequences Sequences that stop generation (optional)
 * @param metadata     Additional request metadata (optional)
 */
public record LlmRequest(
        String userPrompt,
        String systemPrompt,
        String modelId,
        Integer maxTokens,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        Map<String, Object> metadata
) {
    /**
     * Create a standard request with default parameters.
     */
    public static LlmRequest standard(String userPrompt) {
        return new LlmRequest(
                userPrompt,
                null,
                null, // Will use default from config
                null, // Will use default from config
                null, // Will use default from config
                null,
                null,
                null
        );
    }

    /**
     * Create a request with custom system prompt.
     */
    public static LlmRequest withSystemPrompt(String userPrompt, String systemPrompt) {
        return new LlmRequest(
                userPrompt,
                systemPrompt,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Create a request with custom parameters.
     */
    public static LlmRequest custom(
            String userPrompt,
            String systemPrompt,
            String modelId,
            Integer maxTokens,
            Double temperature
    ) {
        return new LlmRequest(
                userPrompt,
                systemPrompt,
                modelId,
                maxTokens,
                temperature,
                null,
                null,
                null
        );
    }

    /**
     * Validate request parameters.
     */
    public boolean isValid() {
        return userPrompt != null && !userPrompt.isBlank();
    }

    /**
     * Check if system prompt is provided.
     */
    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    /**
     * Check if custom model ID is specified.
     */
    public boolean hasCustomModelId() {
        return modelId != null && !modelId.isBlank();
    }

    /**
     * Check if custom parameters are specified.
     */
    public boolean hasCustomParameters() {
        return maxTokens != null || temperature != null || topP != null;
    }
}
