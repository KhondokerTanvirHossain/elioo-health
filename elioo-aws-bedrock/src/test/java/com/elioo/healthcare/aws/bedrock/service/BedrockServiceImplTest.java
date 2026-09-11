package com.elioo.healthcare.aws.bedrock.service;

import com.elioo.healthcare.aws.bedrock.config.BedrockProperties;
import com.elioo.healthcare.aws.bedrock.model.LlmRequest;
import com.elioo.healthcare.aws.bedrock.model.LlmResponse;
import com.elioo.healthcare.aws.common.exception.AwsServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedrockServiceImplTest {

    @Mock
    private BedrockRuntimeAsyncClient bedrockClient;

    private BedrockProperties properties;
    private ObjectMapper objectMapper;
    private BedrockServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new BedrockProperties();
        properties.setModelId("anthropic.claude-3-5-sonnet-20241022-v2:0");
        properties.setMaxTokens(4096);
        properties.setTemperature(0.7);

        objectMapper = new ObjectMapper();
        service = new BedrockServiceImpl(bedrockClient, properties, objectMapper);
    }

    @Test
    void testInvokeModel_Claude_Success() {
        // Arrange
        String claudeResponse = """
                {
                  "content": [{"text": "The weather is sunny today."}],
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 15,
                    "output_tokens": 8
                  }
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(claudeResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.withSystemPrompt(
                "What is the weather?",
                "You are a helpful assistant"
        );

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .assertNext(response -> {
                    assertThat(response.content()).isEqualTo("The weather is sunny today.");
                    assertThat(response.stopReason()).isEqualTo("end_turn");
                    assertThat(response.usage()).isNotNull();
                    assertThat(response.usage().inputTokens()).isEqualTo(15);
                    assertThat(response.usage().outputTokens()).isEqualTo(8);
                    assertThat(response.usage().totalTokens()).isEqualTo(23);
                    assertThat(response.isComplete()).isTrue();
                })
                .verifyComplete();

        // Verify request payload
        ArgumentCaptor<InvokeModelRequest> requestCaptor = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(requestCaptor.capture());

        InvokeModelRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.modelId()).isEqualTo("anthropic.claude-3-5-sonnet-20241022-v2:0");
    }

    @Test
    void testInvokeClaude_ConvenienceMethod() {
        // Arrange
        String claudeResponse = """
                {
                  "content": [{"text": "2+2 equals 4"}],
                  "stop_reason": "end_turn"
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(claudeResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // Act & Assert
        StepVerifier.create(service.invokeClaude("What is 2+2?", "You are a math tutor"))
                .assertNext(response -> {
                    assertThat(response.content()).isEqualTo("2+2 equals 4");
                    assertThat(response.stopReason()).isEqualTo("end_turn");
                })
                .verifyComplete();
    }

    @Test
    void testInvokeModel_Titan_Success() {
        // Arrange
        properties.setModelId("amazon.titan-text-express-v1");

        String titanResponse = """
                {
                  "results": [
                    {
                      "outputText": "Titan generated response",
                      "completionReason": "FINISH",
                      "tokenCount": 10
                    }
                  ],
                  "inputTextTokenCount": 20
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(titanResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.custom(
                "Test prompt",
                null,
                "amazon.titan-text-express-v1",
                null,
                null
        );

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .assertNext(response -> {
                    assertThat(response.content()).isEqualTo("Titan generated response");
                    assertThat(response.stopReason()).isEqualTo("FINISH");
                    assertThat(response.usage()).isNotNull();
                    assertThat(response.usage().inputTokens()).isEqualTo(20);
                    assertThat(response.usage().outputTokens()).isEqualTo(10);
                })
                .verifyComplete();
    }

    @Test
    void testInvokeModel_Llama_Success() {
        // Arrange
        String llamaResponse = """
                {
                  "generation": "Llama response text",
                  "stop_reason": "stop",
                  "prompt_token_count": 25,
                  "generation_token_count": 15
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(llamaResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.custom(
                "Test",
                null,
                "meta.llama3-70b-instruct-v1:0",
                null,
                null
        );

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .assertNext(response -> {
                    assertThat(response.content()).isEqualTo("Llama response text");
                    assertThat(response.usage().inputTokens()).isEqualTo(25);
                    assertThat(response.usage().outputTokens()).isEqualTo(15);
                })
                .verifyComplete();
    }

    @Test
    void testInvokeModel_InvalidRequest_NullPrompt() {
        // Arrange
        LlmRequest request = new LlmRequest(null, null, null, null, null, null, null, null);

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .expectErrorMatches(error ->
                        error instanceof AwsServiceException &&
                        ((AwsServiceException) error).getErrorCode().equals("INVALID_REQUEST")
                )
                .verify();

        verify(bedrockClient, never()).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void testInvokeModel_InvalidRequest_BlankPrompt() {
        // Arrange
        LlmRequest request = LlmRequest.standard("   ");

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .expectErrorMatches(error ->
                        error instanceof AwsServiceException &&
                        ((AwsServiceException) error).getErrorCode().equals("INVALID_REQUEST")
                )
                .verify();
    }

    @Test
    void testInvokeModel_AwsClientError() {
        // Arrange
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("AWS Service Unavailable")
                ));

        LlmRequest request = LlmRequest.standard("Test");

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .expectErrorMatches(error ->
                        error instanceof AwsServiceException &&
                        error.getMessage().contains("Failed to invoke Bedrock model")
                )
                .verify();
    }

    @Test
    void testInvokeModel_InvalidResponseFormat() {
        // Arrange
        String invalidResponse = """
                {
                  "invalid": "format"
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(invalidResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.standard("Test");

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .expectErrorMatches(error ->
                        error instanceof AwsServiceException &&
                        error.getMessage().contains("Invalid Claude response format")
                )
                .verify();
    }

    @Test
    void testInvokeModel_WithCustomParameters() {
        // Arrange
        String claudeResponse = """
                {
                  "content": [{"text": "Custom params response"}],
                  "stop_reason": "end_turn"
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(claudeResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.custom(
                "Test",
                "System prompt",
                "anthropic.claude-3-haiku-20240307-v1:0",
                2000,
                0.5
        );

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .assertNext(response -> {
                    assertThat(response.content()).isEqualTo("Custom params response");
                    assertThat(response.modelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
                })
                .verifyComplete();

        // Verify custom model ID was used
        ArgumentCaptor<InvokeModelRequest> requestCaptor = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(requestCaptor.capture());
        assertThat(requestCaptor.getValue().modelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
    }

    @Test
    void testInvokeModel_MaxTokensReached() {
        // Arrange
        String claudeResponse = """
                {
                  "content": [{"text": "Truncated response..."}],
                  "stop_reason": "max_tokens"
                }
                """;

        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromString(claudeResponse, StandardCharsets.UTF_8))
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        LlmRequest request = LlmRequest.standard("Long prompt");

        // Act & Assert
        StepVerifier.create(service.invokeModel(request))
                .assertNext(response -> {
                    assertThat(response.stopReason()).isEqualTo("max_tokens");
                    assertThat(response.isMaxTokensReached()).isTrue();
                    assertThat(response.isComplete()).isFalse();
                })
                .verifyComplete();
    }
}
