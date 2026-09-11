package com.elioo.healthcare.aws.bedrock.dto;

/**
 * Request DTO for Bedrock invoke model endpoint.
 *
 * @param userPrompt The user's prompt/question
 * @param systemPrompt Optional system prompt to guide the model's behavior
 * @param modelId Optional model ID (defaults to configured Claude model if null)
 * @param maxTokens Optional maximum tokens in response (defaults to 4096 if null)
 * @param temperature Optional temperature for response creativity (defaults to 0.7 if null)
 */
public record InvokeModelRequest(
        String userPrompt,
        String systemPrompt,
        String modelId,
        Integer maxTokens,
        Double temperature
) {
    public static InvokeModelRequest simple(String userPrompt) {
        return new InvokeModelRequest(userPrompt, null, null, null, null);
    }

    public static InvokeModelRequest withSystem(String userPrompt, String systemPrompt) {
        return new InvokeModelRequest(userPrompt, systemPrompt, null, null, null);
    }
}
