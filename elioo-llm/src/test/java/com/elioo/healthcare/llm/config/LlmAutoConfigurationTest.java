package com.elioo.healthcare.llm.config;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class));

    @Test
    void groqIsDefaultAndNeedsKey() {
        runner.withPropertyValues("llm.groq.api-key=gsk_x").run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmClient.class);
            assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(OpenAiCompatibleLlmClient.class);
            assertThat(ctx.getBean(LlmClient.class).providerName()).isEqualTo("groq");
            assertThat(ctx).hasSingleBean(HealthInsightService.class);
        });
    }

    @Test
    void openaiProvider() {
        runner.withPropertyValues("llm.provider=openai", "llm.openai.api-key=sk-x").run(ctx ->
                assertThat(ctx.getBean(LlmClient.class).providerName()).isEqualTo("openai"));
    }

    @Test
    void anthropicProvider() {
        runner.withPropertyValues("llm.provider=anthropic", "llm.anthropic.api-key=sk-ant-x").run(ctx ->
                assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class));
    }

    @Test
    void missingKeyFailsStartupWithClearMessage() {
        runner.withPropertyValues("llm.provider=groq").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("GROQ_API_KEY");
        });
    }

    @Test
    void unknownProviderFails() {
        runner.withPropertyValues("llm.provider=nope").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void bedrockProviderCreatesNoClientHere() {
        // the bedrock module supplies the client; without it the health service cannot be built
        runner.withPropertyValues("llm.provider=bedrock").run(ctx -> assertThat(ctx).hasFailed());
    }
}
