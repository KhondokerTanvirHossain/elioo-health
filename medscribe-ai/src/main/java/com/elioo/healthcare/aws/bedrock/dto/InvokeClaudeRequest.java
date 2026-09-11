package com.elioo.healthcare.aws.bedrock.dto;

/**
 * Request DTO for Bedrock invoke Claude endpoint (convenience method).
 *
 * @param userPrompt The user's prompt/question
 * @param systemPrompt Optional system prompt to guide Claude's behavior
 */
public record InvokeClaudeRequest(
        String userPrompt,
        String systemPrompt
) {
    public static InvokeClaudeRequest simple(String userPrompt) {
        return new InvokeClaudeRequest(userPrompt, null);
    }
}
