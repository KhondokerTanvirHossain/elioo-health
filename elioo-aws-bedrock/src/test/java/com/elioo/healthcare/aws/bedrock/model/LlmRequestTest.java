package com.elioo.healthcare.aws.bedrock.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestTest {

    @Test
    void testStandardRequest() {
        LlmRequest request = LlmRequest.standard("What is the weather?");

        assertThat(request.userPrompt()).isEqualTo("What is the weather?");
        assertThat(request.systemPrompt()).isNull();
        assertThat(request.modelId()).isNull();
        assertThat(request.maxTokens()).isNull();
        assertThat(request.temperature()).isNull();
        assertThat(request.isValid()).isTrue();
        assertThat(request.hasSystemPrompt()).isFalse();
        assertThat(request.hasCustomModelId()).isFalse();
        assertThat(request.hasCustomParameters()).isFalse();
    }

    @Test
    void testWithSystemPrompt() {
        LlmRequest request = LlmRequest.withSystemPrompt(
                "What is 2+2?",
                "You are a helpful math tutor"
        );

        assertThat(request.userPrompt()).isEqualTo("What is 2+2?");
        assertThat(request.systemPrompt()).isEqualTo("You are a helpful math tutor");
        assertThat(request.isValid()).isTrue();
        assertThat(request.hasSystemPrompt()).isTrue();
    }

    @Test
    void testCustomRequest() {
        LlmRequest request = LlmRequest.custom(
                "Analyze this",
                "You are an analyst",
                "anthropic.claude-3-sonnet-20240229-v1:0",
                2000,
                0.5
        );

        assertThat(request.userPrompt()).isEqualTo("Analyze this");
        assertThat(request.systemPrompt()).isEqualTo("You are an analyst");
        assertThat(request.modelId()).isEqualTo("anthropic.claude-3-sonnet-20240229-v1:0");
        assertThat(request.maxTokens()).isEqualTo(2000);
        assertThat(request.temperature()).isEqualTo(0.5);
        assertThat(request.isValid()).isTrue();
        assertThat(request.hasCustomModelId()).isTrue();
        assertThat(request.hasCustomParameters()).isTrue();
    }

    @Test
    void testInvalidRequest_NullPrompt() {
        LlmRequest request = new LlmRequest(null, null, null, null, null, null, null, null);
        assertThat(request.isValid()).isFalse();
    }

    @Test
    void testInvalidRequest_BlankPrompt() {
        LlmRequest request = new LlmRequest("   ", null, null, null, null, null, null, null);
        assertThat(request.isValid()).isFalse();
    }

    @Test
    void testWithStopSequences() {
        LlmRequest request = new LlmRequest(
                "Generate code",
                null,
                null,
                null,
                null,
                null,
                List.of("```", "END"),
                null
        );

        assertThat(request.stopSequences()).containsExactly("```", "END");
    }

    @Test
    void testWithTopP() {
        LlmRequest request = new LlmRequest(
                "Test",
                null,
                null,
                null,
                null,
                0.9,
                null,
                null
        );

        assertThat(request.topP()).isEqualTo(0.9);
        assertThat(request.hasCustomParameters()).isTrue();
    }
}
