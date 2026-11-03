package com.elioo.healthcare.llm.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AnthropicLlmClientTest {

    private AnthropicLlmClient client() {
        LlmProperties props = new LlmProperties();
        props.getAnthropic().setApiKey("sk-test");
        props.getAnthropic().setEffort("high");
        return new AnthropicLlmClient(mock(AnthropicClient.class), props.getAnthropic(), props);
    }

    @Test
    void buildsParamsWithDefaultsThinkingAndEffort() {
        MessageCreateParams p = client().buildParams(LlmRequest.forJson("Summarise HbA1c 7.8%", "You are a clinician."));
        assertThat(p.model().toString()).isEqualTo("claude-opus-5");
        assertThat(p.maxTokens()).isEqualTo(8192L);
        assertThat(p.system()).isPresent();
        assertThat(p.thinking()).isPresent();
        assertThat(p.outputConfig()).isPresent();
        assertThat(p.temperature()).isEmpty();   // never sent to Opus 5
    }

    @Test
    void honoursPerRequestModelAndMaxTokens() {
        MessageCreateParams p = client().buildParams(LlmRequest.custom("hi", null, "claude-sonnet-5", 300, 0.9));
        assertThat(p.model().toString()).isEqualTo("claude-sonnet-5");
        assertThat(p.maxTokens()).isEqualTo(300L);
        assertThat(p.system()).isEmpty();
        assertThat(p.temperature()).isEmpty();
    }

    @Test
    void mapsResponse() {
        LlmResponse r = AnthropicLlmClient.toResponse("{\"a\":1}", "end_turn", 10, 5, "claude-opus-5");
        assertThat(r.content()).isEqualTo("{\"a\":1}");
        assertThat(r.stopReason()).isEqualTo("end_turn");
        assertThat(r.usage().totalTokens()).isEqualTo(15);
        assertThat(r.metadata()).containsEntry("provider", "anthropic");
    }

    @Test
    void refusalIsAnError() {
        assertThatThrownBy(() -> AnthropicLlmClient.toResponse("", "refusal", 10, 0, "claude-opus-5"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("refused");
    }

    /**
     * Haiku 4.5 answers "adaptive thinking is not supported on this model" (400) if either parameter is
     * sent, so the cheap extraction tier would fail on every document. Verified against the live API on
     * 2026-09-17, then pinned here.
     */
    @Test
    void haikuGetsNeitherThinkingNorEffort() {
        MessageCreateParams p = client().buildParams(
                LlmRequest.custom("hi", null, "claude-haiku-4-5-20251001", 300, null));
        assertThat(p.thinking()).isEmpty();
        assertThat(p.outputConfig()).isEmpty();
        assertThat(p.maxTokens()).isEqualTo(300L);
    }

    @Test
    void sonnetStillGetsThinkingAndEffort() {
        MessageCreateParams p = client().buildParams(
                LlmRequest.custom("hi", null, "claude-sonnet-5", 300, null));
        assertThat(p.thinking()).isPresent();
        assertThat(p.outputConfig()).isPresent();
    }

    /**
     * Haiku 5.5 thinks adaptively by default at effort {@code medium} and takes {@code output_config.effort} from low to max
     * (Anthropic's Claude API reference, cached 2026-10-06): the denylist written for Haiku 4.5 must not take its effort away,
     * or a one-line verdict (the চ্যাট's model check, PO ruling P15) thinks at medium inside a small max_tokens.
     */
    @Test
    void haiku55GetsAdaptiveThinkingAndTheConfiguredEffort() {
        LlmProperties props = new LlmProperties();
        props.getAnthropic().setApiKey("test-key");
        props.getAnthropic().setModel("claude-haiku-5-5");
        props.getAnthropic().setEffort("low");
        AnthropicLlmClient haiku = new AnthropicLlmClient(mock(AnthropicClient.class), props.getAnthropic(), props);

        MessageCreateParams p = haiku.buildParams(LlmRequest.custom("hi", null, "claude-haiku-5-5", 4096, null));

        assertThat(p.thinking()).isPresent();
        assertThat(p.thinking().get().isAdaptive()).isTrue();
        assertThat(p.outputConfig()).isPresent();
        assertThat(p.outputConfig().get().effort()).contains(OutputConfig.Effort.LOW);
        // and Haiku 4.5, which rejects both, still gets neither
        MessageCreateParams old = haiku.buildParams(LlmRequest.custom("hi", null, "claude-haiku-4-5", 300, null));
        assertThat(old.thinking()).isEmpty();
        assertThat(old.outputConfig()).isEmpty();
    }

    /** An unrecognised id is assumed modern: better a loud 400 than a silent downgrade. Only Haiku 4.x and older are denied. */
    @Test
    void anUnknownModelKeepsTheModernParameters() {
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-something-7")).isTrue();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-haiku-5-5")).isTrue();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-haiku-9")).isTrue();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-haiku-4-5")).isFalse();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-haiku-4-5-20251001")).isFalse();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-3-5-haiku-20241022")).isFalse();
        assertThat(AnthropicLlmClient.supportsAdaptiveThinking("claude-3-haiku-20240307")).isFalse();
    }
}
