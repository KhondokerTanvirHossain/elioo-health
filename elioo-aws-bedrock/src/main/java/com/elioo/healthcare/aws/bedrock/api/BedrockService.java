package com.elioo.healthcare.aws.bedrock.api;

import com.elioo.healthcare.aws.bedrock.model.LlmRequest;
import com.elioo.healthcare.aws.bedrock.model.LlmResponse;
import reactor.core.publisher.Mono;

/**
 * LLM service contract for language model invocation.
 *
 * <p>This interface defines the contract for Large Language Model (LLM) invocation services.
 * Implementations provide provider-specific LLM capabilities for text generation and
 * conversational AI.</p>
 *
 * <p>All operations are reactive and return {@link Mono} types for non-blocking execution.</p>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code BedrockServiceImpl} - AWS Bedrock implementation (supports Claude, Titan, Llama, etc.)</li>
 * </ul>
 *
 * @see LlmRequest
 * @see LlmResponse
 * @since 0.1.0
 */
public interface BedrockService {

    /**
     * Invokes a language model with the given request.
     *
     * <p>This method provides a unified interface for invoking various LLM providers.
     * The specific model and configuration are determined by the request parameters.</p>
     *
     * <p>Supported model families include:</p>
     * <ul>
     *   <li>Anthropic Claude (3, 3.5 Sonnet/Haiku/Opus)</li>
     *   <li>Amazon Titan Text</li>
     *   <li>Meta Llama 2/3</li>
     *   <li>AI21 Jurassic</li>
     * </ul>
     *
     * @param request LLM invocation request containing prompt, model configuration, and parameters
     * @return Mono emitting the LLM response with generated text and metadata
     */
    Mono<LlmResponse> invokeModel(LlmRequest request);

    /**
     * Invokes a Claude model specifically (convenience method).
     *
     * <p>This is a convenience method that automatically configures the request for Claude models.
     * Use this when you specifically want to use Anthropic's Claude models.</p>
     *
     * @param userPrompt User's prompt/question
     * @param systemPrompt System instructions to guide model behavior (optional, can be null)
     * @return Mono emitting the Claude response with generated text and metadata
     */
    Mono<LlmResponse> invokeClaude(String userPrompt, String systemPrompt);
}
