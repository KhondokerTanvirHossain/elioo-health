package com.elioo.healthcare.aws.bedrock.service;

import com.elioo.healthcare.aws.bedrock.config.BedrockProperties;
import com.elioo.healthcare.aws.bedrock.model.LlmRequest;
import com.elioo.healthcare.aws.bedrock.model.LlmResponse;
import com.elioo.healthcare.aws.bedrock.model.TokenUsage;
import com.elioo.healthcare.aws.common.exception.AwsServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AWS Bedrock implementation of LLM service.
 *
 * <p>This implementation uses Amazon Bedrock for language model invocation.
 * It supports multiple model providers including:</p>
 * <ul>
 *   <li>Anthropic Claude (3, 3.5 Sonnet/Haiku/Opus)</li>
 *   <li>Amazon Titan Text</li>
 *   <li>Meta Llama 2/3</li>
 *   <li>AI21 Jurassic</li>
 * </ul>
 *
 * <p>Provides a unified interface for LLM invocation while handling
 * provider-specific request/response formats.</p>
 *
 * @see com.elioo.healthcare.aws.bedrock.api.BedrockService
 * @since 0.1.0
 */
public class BedrockServiceImpl implements com.elioo.healthcare.aws.bedrock.api.BedrockService {

    private static final Logger log = LoggerFactory.getLogger(BedrockServiceImpl.class);

    private final BedrockRuntimeAsyncClient bedrockClient;
    private final BedrockProperties properties;
    private final ObjectMapper objectMapper;

    public BedrockServiceImpl(
            BedrockRuntimeAsyncClient bedrockClient,
            BedrockProperties properties,
            ObjectMapper objectMapper
    ) {
        this.bedrockClient = bedrockClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Invoke a language model with the given request.
     *
     * @param request LLM invocation request
     * @return LLM response wrapped in Mono
     */
    public Mono<LlmResponse> invokeModel(LlmRequest request) {
        if (!request.isValid()) {
            return Mono.error(new AwsServiceException(
                    "Bedrock",
                    "INVALID_REQUEST",
                    400,
                    "User prompt is required",
                    null
            ));
        }

        String modelId = request.hasCustomModelId() ? request.modelId() : properties.getModelId();

        log.info("Invoking Bedrock model: {}", modelId);
        if (properties.isLogRequests()) {
            log.info("Request - Model: {}, Prompt length: {}", modelId, request.userPrompt().length());
        }

        return buildRequestPayload(request, modelId)
                .flatMap(payload -> invokeBedrockModel(modelId, payload))
                .doOnSuccess(response -> log.info("Bedrock model response: {}", response))
                .map(response -> parseResponse(response, modelId))
                .doOnSuccess(response -> {
                    if (properties.isLogRequests()) {
                        log.info("Response - Content length: {}, Tokens: {}",
                                response.getContentLength(),
                                response.getTotalTokens());
                    }
                })
                .onErrorMap(this::mapError);
    }

    /**
     * Invoke a Claude model specifically (convenience method).
     *
     * @param userPrompt   User's prompt
     * @param systemPrompt System instructions (optional)
     * @return LLM response wrapped in Mono
     */
    public Mono<LlmResponse> invokeClaude(String userPrompt, String systemPrompt) {
        LlmRequest request = LlmRequest.withSystemPrompt(userPrompt, systemPrompt);
        return invokeModel(request);
    }

    /**
     * Build request payload based on model provider.
     */
    private Mono<String> buildRequestPayload(LlmRequest request, String modelId) {
        try {
            Map<String, Object> payload;

            if (isClaudeModel(modelId)) {
                payload = buildClaudePayload(request);
            } else if (isTitanModel(modelId)) {
                payload = buildTitanPayload(request);
            } else if (isLlamaModel(modelId)) {
                payload = buildLlamaPayload(request);
            } else {
                // Generic payload format
                payload = buildGenericPayload(request);
            }

            String json = objectMapper.writeValueAsString(payload);
            return Mono.just(json);

        } catch (JsonProcessingException e) {
            return Mono.error(new AwsServiceException(
                    "Bedrock",
                    "PAYLOAD_BUILD_ERROR",
                    500,
                    "Failed to build request payload: " + e.getMessage(),
                    e
            ));
        }
    }

    /**
     * Build Claude-specific payload.
     * Format: https://docs.anthropic.com/claude/reference/messages_post
     */
    private Map<String, Object> buildClaudePayload(LlmRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("anthropic_version", "bedrock-2023-05-31");
        payload.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : properties.getMaxTokens());
        payload.put("temperature", request.temperature() != null ? request.temperature() : properties.getTemperature());

        if (request.hasSystemPrompt()) {
            payload.put("system", request.systemPrompt());
        }

        if (request.topP() != null) {
            payload.put("top_p", request.topP());
        } else if (properties.getTopP() != null) {
            payload.put("top_p", properties.getTopP());
        }

        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            payload.put("stop_sequences", request.stopSequences());
        }

        // Messages format
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", request.userPrompt());
        messages.add(userMessage);
        payload.put("messages", messages);

        return payload;
    }

    /**
     * Build Amazon Titan payload.
     */
    private Map<String, Object> buildTitanPayload(LlmRequest request) {
        Map<String, Object> payload = new HashMap<>();

        String fullPrompt = request.hasSystemPrompt()
                ? request.systemPrompt() + "\n\n" + request.userPrompt()
                : request.userPrompt();

        payload.put("inputText", fullPrompt);

        Map<String, Object> textGenerationConfig = new HashMap<>();
        textGenerationConfig.put("maxTokenCount", request.maxTokens() != null ? request.maxTokens() : properties.getMaxTokens());
        textGenerationConfig.put("temperature", request.temperature() != null ? request.temperature() : properties.getTemperature());

        if (request.topP() != null) {
            textGenerationConfig.put("topP", request.topP());
        } else if (properties.getTopP() != null) {
            textGenerationConfig.put("topP", properties.getTopP());
        }

        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            textGenerationConfig.put("stopSequences", request.stopSequences());
        }

        payload.put("textGenerationConfig", textGenerationConfig);

        return payload;
    }

    /**
     * Build Meta Llama payload.
     */
    private Map<String, Object> buildLlamaPayload(LlmRequest request) {
        Map<String, Object> payload = new HashMap<>();

        String fullPrompt = request.hasSystemPrompt()
                ? request.systemPrompt() + "\n\n" + request.userPrompt()
                : request.userPrompt();

        payload.put("prompt", fullPrompt);
        payload.put("max_gen_len", request.maxTokens() != null ? request.maxTokens() : properties.getMaxTokens());
        payload.put("temperature", request.temperature() != null ? request.temperature() : properties.getTemperature());

        if (request.topP() != null) {
            payload.put("top_p", request.topP());
        } else if (properties.getTopP() != null) {
            payload.put("top_p", properties.getTopP());
        }

        return payload;
    }

    /**
     * Build generic payload (fallback).
     */
    private Map<String, Object> buildGenericPayload(LlmRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", request.userPrompt());
        payload.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : properties.getMaxTokens());
        payload.put("temperature", request.temperature() != null ? request.temperature() : properties.getTemperature());
        return payload;
    }

    /**
     * Invoke Bedrock model via AWS SDK.
     */
    private Mono<InvokeModelResponse> invokeBedrockModel(String modelId, String payload) {
        InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                .build();
        log.info("Invoke Bedrock Model: {}", invokeRequest);
        log.info("Invoke Bedrock Payload: {}", payload);
        return Mono.fromFuture(bedrockClient.invokeModel(invokeRequest))
                .doOnError(error -> log.error("Bedrock invocation failed for model {}: {}",
                        modelId, error.getMessage()));
    }

    /**
     * Parse response based on model provider.
     */
    private LlmResponse parseResponse(InvokeModelResponse response, String modelId) {
        try {
            String responseBody = response.body().asUtf8String();
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            if (isClaudeModel(modelId)) {
                return parseClaudeResponse(jsonNode, modelId);
            } else if (isTitanModel(modelId)) {
                return parseTitanResponse(jsonNode, modelId);
            } else if (isLlamaModel(modelId)) {
                return parseLlamaResponse(jsonNode, modelId);
            } else {
                return parseGenericResponse(jsonNode, modelId);
            }

        } catch (Exception e) {
            throw new AwsServiceException(
                    "Bedrock",
                    "RESPONSE_PARSE_ERROR",
                    500,
                    "Failed to parse Bedrock response: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Parse Claude response.
     * Format: { "content": [{"text": "..."}], "stop_reason": "...", "usage": {...} }
     */
    private LlmResponse parseClaudeResponse(JsonNode jsonNode, String modelId) {
        // Extract content
        JsonNode contentArray = jsonNode.get("content");
        if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
            throw new AwsServiceException(
                    "Bedrock",
                    "INVALID_RESPONSE_FORMAT",
                    500,
                    "Invalid Claude response format: missing content array",
                    null
            );
        }
        String content = contentArray.get(0).get("text").asText();

        // Extract stop reason
        String stopReason = jsonNode.has("stop_reason") ? jsonNode.get("stop_reason").asText() : null;

        // Extract usage
        TokenUsage usage = null;
        if (jsonNode.has("usage")) {
            JsonNode usageNode = jsonNode.get("usage");
            int inputTokens = usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : 0;
            int outputTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0;
            usage = new TokenUsage(inputTokens, outputTokens);
        }

        return new LlmResponse(content, stopReason, usage, modelId, null);
    }

    /**
     * Parse Amazon Titan response.
     */
    private LlmResponse parseTitanResponse(JsonNode jsonNode, String modelId) {
        JsonNode resultsArray = jsonNode.get("results");
        if (resultsArray == null || !resultsArray.isArray() || resultsArray.isEmpty()) {
            throw new AwsServiceException(
                    "Bedrock",
                    "INVALID_RESPONSE_FORMAT",
                    500,
                    "Invalid Titan response format",
                    null
            );
        }

        String content = resultsArray.get(0).get("outputText").asText();
        String stopReason = resultsArray.get(0).has("completionReason")
                ? resultsArray.get(0).get("completionReason").asText()
                : null;

        // Titan usage
        TokenUsage usage = null;
        if (jsonNode.has("inputTextTokenCount") && jsonNode.has("results")) {
            int inputTokens = jsonNode.get("inputTextTokenCount").asInt();
            int outputTokens = resultsArray.get(0).has("tokenCount")
                    ? resultsArray.get(0).get("tokenCount").asInt()
                    : 0;
            usage = new TokenUsage(inputTokens, outputTokens);
        }

        return new LlmResponse(content, stopReason, usage, modelId, null);
    }

    /**
     * Parse Meta Llama response.
     */
    private LlmResponse parseLlamaResponse(JsonNode jsonNode, String modelId) {
        String content = jsonNode.has("generation")
                ? jsonNode.get("generation").asText()
                : jsonNode.asText();

        String stopReason = jsonNode.has("stop_reason") ? jsonNode.get("stop_reason").asText() : null;

        // Llama usage
        TokenUsage usage = null;
        if (jsonNode.has("prompt_token_count") && jsonNode.has("generation_token_count")) {
            int inputTokens = jsonNode.get("prompt_token_count").asInt();
            int outputTokens = jsonNode.get("generation_token_count").asInt();
            usage = new TokenUsage(inputTokens, outputTokens);
        }

        return new LlmResponse(content, stopReason, usage, modelId, null);
    }

    /**
     * Parse generic response (fallback).
     */
    private LlmResponse parseGenericResponse(JsonNode jsonNode, String modelId) {
        // Try common field names
        String content = null;
        if (jsonNode.has("completion")) {
            content = jsonNode.get("completion").asText();
        } else if (jsonNode.has("text")) {
            content = jsonNode.get("text").asText();
        } else if (jsonNode.has("output")) {
            content = jsonNode.get("output").asText();
        } else {
            content = jsonNode.asText();
        }

        return LlmResponse.simple(content);
    }

    /**
     * Model type checks.
     */
    private boolean isClaudeModel(String modelId) {
        return modelId != null && modelId.contains("anthropic.claude");
    }

    private boolean isTitanModel(String modelId) {
        return modelId != null && modelId.contains("amazon.titan");
    }

    private boolean isLlamaModel(String modelId) {
        return modelId != null && modelId.contains("meta.llama");
    }

    /**
     * Map AWS SDK errors to domain exceptions.
     */
    private Throwable mapError(Throwable error) {
        if (error instanceof AwsServiceException) {
            return error;
        }

        log.error("Bedrock error: {}", error.getMessage(), error);
        return new AwsServiceException(
                "Bedrock",
                "INVOCATION_ERROR",
                500,
                "Failed to invoke Bedrock model: " + error.getMessage(),
                error
        );
    }
}
