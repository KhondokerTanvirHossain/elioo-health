package com.elioo.baymax.extraction.config;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration.ExtractionClients;
import com.elioo.healthcare.llm.api.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which client answers a Baymax document. This is guarded because getting it wrong is silent: the run
 * completes, a table is printed, and only the {@code models used} line reveals that the numbers belong to
 * a different model than the one the run was named after.
 */
class ExtractionModelConfigurationTest {

    private final ExtractionModelConfiguration config = new ExtractionModelConfiguration();
    private final BaymaxProperties properties = new BaymaxProperties();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        // The default-client lookup uses stream(), not getIfAvailable: a context may hold more than one
        // LlmClient and getIfAvailable cannot choose between them.
        when(p.stream()).thenAnswer(i -> value == null ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(value));
        return p;
    }

    /**
     * The regression: with the Baymax beans unreachable by qualifier, {@code cheap} fell back to
     * MedScribe's Groq client, {@code sendImages} went false, and a run labelled "haiku" was answered end
     * to end by gpt-oss-120b.
     */
    @Test
    void theCheapTierIsTheVisionClientNotMedscribesDefault() {
        LlmClient medscribeDefault = mock(LlmClient.class);
        LlmClient haiku = mock(LlmClient.class);
        when(haiku.supportsImages()).thenReturn(true);

        ExtractionClients clients = config.extractionClients(
                provider(medscribeDefault),
                provider(new BaymaxModelClient(haiku)),
                provider(null),
                properties);

        assertThat(clients.cheap()).isSameAs(haiku);
        assertThat(clients.primary()).isSameAs(haiku);
        assertThat(clients.sendImages()).isTrue();
    }

    /** With no Baymax client configured at all, MedScribe's default is the only thing left to use. */
    @Test
    void withoutAVisionClientTheDefaultStands() {
        LlmClient medscribeDefault = mock(LlmClient.class);

        ExtractionClients clients = config.extractionClients(
                provider(medscribeDefault), provider(null), provider(null), properties);

        assertThat(clients.cheap()).isSameAs(medscribeDefault);
        assertThat(clients.sendImages()).isFalse();
    }

    @Test
    void theStrongClientIsSeparateFromTheCheapOne() {
        LlmClient haiku = mock(LlmClient.class);
        when(haiku.supportsImages()).thenReturn(true);
        LlmClient sonnet = mock(LlmClient.class);

        ExtractionClients clients = config.extractionClients(
                provider(null), provider(new BaymaxModelClient(haiku)),
                provider(new BaymaxModelClient(sonnet)), properties);

        assertThat(clients.strong()).isSameAs(sonnet);
        assertThat(clients.strong()).isNotSameAs(clients.cheap());
    }

    @Test
    void anthropicModelIdsAreRecognised() {
        assertThat(ExtractionModelConfiguration.isAnthropicModel("claude-haiku-4-5")).isTrue();
        assertThat(ExtractionModelConfiguration.isAnthropicModel("claude-sonnet-5")).isTrue();
        assertThat(ExtractionModelConfiguration.isAnthropicModel("qwen/qwen3.8-27b")).isFalse();
        assertThat(ExtractionModelConfiguration.isAnthropicModel(null)).isFalse();
    }
}
