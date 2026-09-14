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
 * @param images        Images to send alongside the prompt (optional). Providers that cannot accept
 *                      images ignore them; see {@code LlmClient.supportsImages()}.
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
        boolean jsonOutput,
        List<LlmImage> images
) {
    /** Backwards-compatible constructor: no images. Every pre-BMX-2 caller lands here. */
    public LlmRequest(String userPrompt, String systemPrompt, String modelId, Integer maxTokens,
                      Double temperature, Double topP, List<String> stopSequences,
                      Map<String, Object> metadata, boolean jsonOutput) {
        this(userPrompt, systemPrompt, modelId, maxTokens, temperature, topP, stopSequences,
                metadata, jsonOutput, List.of());
    }

    public LlmRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** This request with the given images attached. */
    public LlmRequest withImages(List<LlmImage> attached) {
        return new LlmRequest(userPrompt, systemPrompt, modelId, maxTokens, temperature, topP,
                stopSequences, metadata, jsonOutput, attached);
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
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
