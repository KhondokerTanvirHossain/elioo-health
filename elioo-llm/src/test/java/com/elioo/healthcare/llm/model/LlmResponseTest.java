package com.elioo.healthcare.llm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResponseTest {

    @Test
    void testSimpleResponse() {
        LlmResponse response = LlmResponse.simple("Hello, world!");

        assertThat(response.content()).isEqualTo("Hello, world!");
        assertThat(response.stopReason()).isNull();
        assertThat(response.usage()).isNull();
        assertThat(response.modelId()).isNull();
        assertThat(response.hasContent()).isTrue();
        assertThat(response.hasUsage()).isFalse();
        assertThat(response.getContentLength()).isEqualTo(13);
        assertThat(response.getTotalTokens()).isEqualTo(0);
    }

    @Test
    void testWithUsage() {
        TokenUsage usage = new TokenUsage(100, 50);
        LlmResponse response = LlmResponse.withUsage("Generated text", usage);

        assertThat(response.content()).isEqualTo("Generated text");
        assertThat(response.usage()).isEqualTo(usage);
        assertThat(response.hasUsage()).isTrue();
        assertThat(response.getTotalTokens()).isEqualTo(150);
    }

    @Test
    void testCompleteResponse() {
        TokenUsage usage = new TokenUsage(200, 100);
        LlmResponse response = new LlmResponse(
                "Complete response",
                "end_turn",
                usage,
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                null
        );

        assertThat(response.content()).isEqualTo("Complete response");
        assertThat(response.stopReason()).isEqualTo("end_turn");
        assertThat(response.usage()).isEqualTo(usage);
        assertThat(response.modelId()).isEqualTo("anthropic.claude-3-5-sonnet-20241022-v2:0");
        assertThat(response.isComplete()).isTrue();
        assertThat(response.isMaxTokensReached()).isFalse();
    }

    @Test
    void testMaxTokensReached() {
        LlmResponse response1 = new LlmResponse("Text", "max_tokens", null, null, null);
        LlmResponse response2 = new LlmResponse("Text", "length", null, null, null);

        assertThat(response1.isMaxTokensReached()).isTrue();
        assertThat(response2.isMaxTokensReached()).isTrue();
        assertThat(response1.isComplete()).isFalse();
    }

    @Test
    void testStopSequence() {
        LlmResponse response = new LlmResponse("Text", "stop_sequence", null, null, null);

        assertThat(response.isComplete()).isTrue();
        assertThat(response.isMaxTokensReached()).isFalse();
    }

    @Test
    void testEmptyContent() {
        LlmResponse response = LlmResponse.simple("");

        assertThat(response.hasContent()).isFalse();
        assertThat(response.getContentLength()).isEqualTo(0);
    }

    @Test
    void testNullContent() {
        LlmResponse response = new LlmResponse(null, null, null, null, null);

        assertThat(response.hasContent()).isFalse();
        assertThat(response.getContentLength()).isEqualTo(0);
    }

    @Test
    void timedStampsProviderAndLatencyWithoutTouchingTheRest() {
        LlmResponse base = new LlmResponse("Text", "end_turn", new TokenUsage(10, 5), "m", null);
        assertThat(base.provider()).isNull();
        assertThat(base.latencyMs()).isNull();

        LlmResponse timed = base.timed("groq", 321L);
        assertThat(timed.provider()).isEqualTo("groq");
        assertThat(timed.latencyMs()).isEqualTo(321L);
        assertThat(timed.content()).isEqualTo("Text");
        assertThat(timed.usage()).isEqualTo(new TokenUsage(10, 5));
        assertThat(timed.modelId()).isEqualTo("m");
    }
}
