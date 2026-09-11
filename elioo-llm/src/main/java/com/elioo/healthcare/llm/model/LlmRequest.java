package com.elioo.healthcare.llm.model;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral LLM invocation request.
 *
 * @param userPrompt    The user's prompt
 * @param systemPrompt  System instructions (optional)
 * @param modelId       Provider model identifier (optional; provider default when null)
 * @param maxTokens     Maximum tokens to generate (optional)
 * @param temperature   Sampling temperature (optional; ignored by providers that reject it)
 * @param topP          Nucleus sampling (optional)
 * @param stopSequences Stop sequences (optional)
 * @param metadata      Free-form metadata (optional)
 * @param jsonOutput    True when the caller will parse the reply as JSON; providers with a
 *                      JSON mode enable it, others rely on the prompt
 */
public record LlmRequest(
        String userPrompt,
        String systemPrompt,
        String modelId,
        Integer maxTokens,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        Map<String, Object> metadata,
        boolean jsonOutput
) {
    /** Create a standard request with provider defaults. */
    public static LlmRequest standard(String userPrompt) {
        return new LlmRequest(userPrompt, null, null, null, null, null, null, null, false);
    }

    /** Create a request with a system prompt. */
    public static LlmRequest withSystemPrompt(String userPrompt, String systemPrompt) {
        return new LlmRequest(userPrompt, systemPrompt, null, null, null, null, null, null, false);
    }

    /** A request whose reply must be JSON (the prompt already says so). */
    public static LlmRequest forJson(String userPrompt, String systemPrompt) {
        return new LlmRequest(userPrompt, systemPrompt, null, null, null, null, null, null, true);
    }

    /** Create a request with custom model and sampling parameters. */
    public static LlmRequest custom(
            String userPrompt,
            String systemPrompt,
            String modelId,
            Integer maxTokens,
            Double temperature
    ) {
        return new LlmRequest(userPrompt, systemPrompt, modelId, maxTokens, temperature, null, null, null, false);
    }

    public boolean isValid() {
        return userPrompt != null && !userPrompt.isBlank();
    }

    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    public boolean hasCustomModelId() {
        return modelId != null && !modelId.isBlank();
    }

    public boolean hasCustomParameters() {
        return maxTokens != null || temperature != null || topP != null;
    }
}
