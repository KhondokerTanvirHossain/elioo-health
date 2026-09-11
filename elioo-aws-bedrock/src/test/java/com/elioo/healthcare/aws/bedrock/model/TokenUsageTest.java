package com.elioo.healthcare.aws.bedrock.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TokenUsageTest {

    @Test
    void testTotalTokens() {
        TokenUsage usage = new TokenUsage(100, 50);

        assertThat(usage.inputTokens()).isEqualTo(100);
        assertThat(usage.outputTokens()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(150);
    }

    @Test
    void testEstimateCost() {
        TokenUsage usage = new TokenUsage(1000, 500);

        // Example: $0.003 per 1K input tokens, $0.015 per 1K output tokens
        double cost = usage.estimateCost(0.003, 0.015);

        // Expected: (1000/1000 * 0.003) + (500/1000 * 0.015) = 0.003 + 0.0075 = 0.0105
        assertThat(cost).isCloseTo(0.0105, within(0.0001));
    }

    @Test
    void testEstimateCost_ClaudeSonnet() {
        // Claude 3.5 Sonnet pricing: $0.003/1K input, $0.015/1K output
        TokenUsage usage = new TokenUsage(2000, 1000);

        double cost = usage.estimateCost(0.003, 0.015);

        // (2000/1000 * 0.003) + (1000/1000 * 0.015) = 0.006 + 0.015 = 0.021
        assertThat(cost).isCloseTo(0.021, within(0.0001));
    }

    @Test
    void testHasUsage() {
        TokenUsage usage1 = new TokenUsage(100, 50);
        TokenUsage usage2 = new TokenUsage(0, 0);
        TokenUsage usage3 = new TokenUsage(0, 10);

        assertThat(usage1.hasUsage()).isTrue();
        assertThat(usage2.hasUsage()).isFalse();
        assertThat(usage3.hasUsage()).isTrue();
    }

    @Test
    void testEmpty() {
        TokenUsage usage = TokenUsage.empty();

        assertThat(usage.inputTokens()).isEqualTo(0);
        assertThat(usage.outputTokens()).isEqualTo(0);
        assertThat(usage.totalTokens()).isEqualTo(0);
        assertThat(usage.hasUsage()).isFalse();
    }

    @Test
    void testEstimateCost_ZeroTokens() {
        TokenUsage usage = TokenUsage.empty();
        double cost = usage.estimateCost(0.003, 0.015);

        assertThat(cost).isEqualTo(0.0);
    }

    @Test
    void testEstimateCost_OnlyInput() {
        TokenUsage usage = new TokenUsage(500, 0);
        double cost = usage.estimateCost(0.003, 0.015);

        // 500/1000 * 0.003 = 0.0015
        assertThat(cost).isCloseTo(0.0015, within(0.0001));
    }

    @Test
    void testEstimateCost_OnlyOutput() {
        TokenUsage usage = new TokenUsage(0, 200);
        double cost = usage.estimateCost(0.003, 0.015);

        // 200/1000 * 0.015 = 0.003
        assertThat(cost).isCloseTo(0.003, within(0.0001));
    }
}
