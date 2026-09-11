package com.elioo.healthcare.llm.dto;

/** Body of {@code POST /api/llm/invoke}: a raw prompt to the configured provider. */
public record InvokeRequest(String userPrompt, String systemPrompt, String modelId,
                            Integer maxTokens, Double temperature, Boolean jsonOutput) {
}
