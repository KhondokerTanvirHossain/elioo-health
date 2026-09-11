package com.elioo.healthcare.aws.bedrock.handler;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.dto.InvokeClaudeRequest;
import com.elioo.healthcare.aws.bedrock.dto.InvokeModelRequest;
import com.elioo.healthcare.aws.bedrock.model.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Web handler for generic AWS Bedrock API endpoints.
 *
 * <p>Exposes direct access to AWS Bedrock LLM services via REST endpoints:
 * <ul>
 *   <li>POST /api/aws/bedrock/invoke-model - Invoke any Bedrock model with full control</li>
 *   <li>POST /api/aws/bedrock/invoke-claude - Convenience endpoint for Claude models</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BedrockApiHandler {

    private final BedrockService bedrockService;

    /**
     * Invoke any Bedrock model with customizable parameters.
     *
     * @param request ServerRequest containing InvokeModelRequest
     * @return ServerResponse with LLM response
     */
    public Mono<ServerResponse> invokeModel(ServerRequest request) {
        return request.bodyToMono(InvokeModelRequest.class)
                .doOnNext(req -> log.info("Invoking Bedrock model: {}",
                        req.modelId() != null ? req.modelId() : "default"))
                .flatMap(req -> {
                    // Build LLM request from API request using static factory method
                    LlmRequest llmRequest = LlmRequest.custom(
                            req.userPrompt(),
                            req.systemPrompt(),
                            req.modelId(),
                            req.maxTokens(),
                            req.temperature()
                    );

                    return bedrockService.invokeModel(llmRequest);
                })
                .flatMap(response -> {
                    log.info("Model invocation completed. Tokens used: {} input, {} output",
                            response.usage() != null ? response.usage().inputTokens() : 0,
                            response.usage() != null ? response.usage().outputTokens() : 0);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Invoke Claude model (convenience endpoint).
     *
     * @param request ServerRequest containing InvokeClaudeRequest
     * @return ServerResponse with LLM response
     */
    public Mono<ServerResponse> invokeClaude(ServerRequest request) {
        return request.bodyToMono(InvokeClaudeRequest.class)
                .doOnNext(req -> log.info("Invoking Claude model"))
                .flatMap(req -> bedrockService.invokeClaude(
                        req.userPrompt(),
                        req.systemPrompt()
                ))
                .flatMap(response -> {
                    log.info("Claude invocation completed. Tokens used: {} input, {} output",
                            response.usage() != null ? response.usage().inputTokens() : 0,
                            response.usage() != null ? response.usage().outputTokens() : 0);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate error response.
     *
     * @param error The exception that occurred
     * @return ServerResponse with error details
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Bedrock API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "bedrock"
                ));
    }
}
