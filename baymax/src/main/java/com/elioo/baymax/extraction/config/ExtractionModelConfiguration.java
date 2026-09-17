package com.elioo.baymax.extraction.config;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The extraction pipeline needs up to three model clients, and the application's default
 * {@link LlmClient} is only one of them:
 *
 * <ul>
 *   <li><b>cheap text</b> — the default client ({@code llm.provider}, normally Groq gpt-oss-120b).</li>
 *   <li><b>cheap vision</b> — the same provider and key but a vision-capable model, because a text-only
 *       model rejects an array-shaped user message outright. Built here rather than configured globally so
 *       MedScribe keeps the client it has.</li>
 *   <li><b>strong</b> — the escalation model (DR-3: the anthropic provider), taken from the context when
 *       that provider is configured.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ExtractionModelConfiguration {

    public static final String VISION_CLIENT = "baymaxVisionLlmClient";
    public static final String STRONG_CLIENT = "baymaxStrongLlmClient";

    /** Anthropic model ids all start with {@code claude-}; everything else is OpenAI-compatible. */
    public static boolean isAnthropicModel(String modelId) {
        return modelId != null && modelId.toLowerCase(java.util.Locale.ROOT).startsWith("claude-");
    }

    /**
     * The cheap-tier client, pinned to the configured vision model. Absent when no vision model is
     * configured or its provider has no key, in which case extraction runs text-only.
     *
     * <p>Which provider that is follows the model id, not {@code llm.provider}: since DR-9 the cheap tier
     * is {@code claude-haiku-4-5} on Anthropic while MedScribe's default client may still be Groq, so the
     * two are deliberately decoupled. A {@code claude-*} id builds an Anthropic client; anything else is
     * OpenAI-compatible, which is how a Groq model id keeps working if the account is ever upgraded.
     */
    // Conditional on a key being present, not just a model id. The cheap tier now defaults to a real
    // model (DR-9), so throwing when the key is absent would stop a context with no LLM configured from
    // starting at all — a sliced test, or an install that has not been given keys yet. Absent bean means
    // extraction runs text-only and fails at call time with a clear message, which is the documented
    // behaviour of every other client here.
    // The key the condition checks must be the key the branch below will actually use: a context holding
    // only a Groq key would otherwise satisfy an `or` and then throw inside the Anthropic branch, which
    // is how this first broke the application context test.
    @Bean(VISION_CLIENT)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.extract.vision-model:}')"
            + " and ("
            + "  ('${baymax.extract.vision-model:}'.toLowerCase().startsWith('claude-')"
            + "     ? T(org.springframework.util.StringUtils).hasText('${llm.anthropic.api-key:}')"
            + "     : (T(org.springframework.util.StringUtils).hasText('${llm.groq.api-key:}')"
            + "        or T(org.springframework.util.StringUtils).hasText('${llm.openai.api-key:}')))"
            + ")")
    public BaymaxModelClient baymaxVisionLlmClient(BaymaxProperties baymax, LlmProperties llm,
                                           ObjectProvider<WebClient> webClients,
                                           ObjectProvider<ObjectMapper> mappers) {
        String model = baymax.getExtract().getVisionModel();

        if (isAnthropicModel(model)) {
            if (!StringUtils.hasText(llm.getAnthropic().getApiKey())) {
                throw new IllegalStateException("baymax.extract.vision-model is " + model
                        + " but the anthropic provider has no API key; set ANTHROPIC_API_KEY");
            }
            LlmProperties.Anthropic cheap = new LlmProperties.Anthropic();
            cheap.setApiKey(llm.getAnthropic().getApiKey());
            cheap.setModel(model);
            cheap.setEffort(llm.getAnthropic().getEffort());
            log.info("[baymax] cheap extraction client: provider=anthropic model={}", model);
            return new BaymaxModelClient(new AnthropicLlmClient(cheap, llm));
        }

        LlmProperties.OpenAiCompatible source = "openai".equalsIgnoreCase(llm.getProvider())
                ? llm.getOpenai() : llm.getGroq();
        if (!StringUtils.hasText(source.getApiKey())) {
            throw new IllegalStateException("baymax.extract.vision-model is set but the "
                    + llm.getProvider() + " provider has no API key; clear the vision model to run text-only");
        }
        LlmProperties.OpenAiCompatible vision = new LlmProperties.OpenAiCompatible();
        vision.setApiKey(source.getApiKey());
        vision.setBaseUrl(source.getBaseUrl());
        vision.setModel(model);
        vision.setSupportsImages(true);
        String provider = "openai".equalsIgnoreCase(llm.getProvider()) ? "openai" : "groq";
        log.info("[baymax] vision extraction client: provider={} model={}", provider, model);
        return new BaymaxModelClient(new OpenAiCompatibleLlmClient(provider, vision, llm,
                webClients.getIfAvailable(WebClient::create), mappers.getIfUnique(ObjectMapper::new)));
    }

    /**
     * The escalation client, built from {@code baymax.extract.strong-model} and the Anthropic key.
     *
     * <p>Built here rather than borrowed from the application default: before DR-9 the strong tier was
     * simply "the default client, when {@code llm.provider=anthropic}", which silently produced no strong
     * client at all whenever the default was Groq. Now that the cheap tier is Anthropic too, the two tiers
     * are separate clients on the same key and escalation no longer depends on MedScribe's provider.
     */
    // The condition covers the key as well as the model id: a @Bean method that returns null leaves a
    // null in the context and broke five container tests the last time this configuration did it.
    @Bean(STRONG_CLIENT)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.extract.strong-model:}')"
            + " and T(org.springframework.util.StringUtils).hasText('${llm.anthropic.api-key:}')")
    public BaymaxModelClient baymaxStrongLlmClient(BaymaxProperties baymax, LlmProperties llm) {
        String model = baymax.getExtract().getStrongModel();
        LlmProperties.Anthropic strong = new LlmProperties.Anthropic();
        strong.setApiKey(llm.getAnthropic().getApiKey());
        strong.setModel(model);
        strong.setEffort(llm.getAnthropic().getEffort());
        log.info("[baymax] strong extraction client: provider=anthropic model={}", model);
        return new BaymaxModelClient(new AnthropicLlmClient(strong, llm));
    }

    /**
     * The clients extraction will use. All are optional so that a context without an LLM provider (a
     * sliced test, or an install with no key) still starts; a call then fails at call time with a clear
     * message rather than preventing the module from loading.
     */
    @Bean
    public ExtractionClients extractionClients(ObjectProvider<LlmClient> clients,
                                               @Qualifier(VISION_CLIENT) ObjectProvider<BaymaxModelClient> visionClient,
                                               @Qualifier(STRONG_CLIENT) ObjectProvider<BaymaxModelClient> strongClient,
                                               BaymaxProperties properties) {
        LlmClient vision = BaymaxModelClient.unwrap(visionClient.getIfAvailable());
        LlmClient strong = BaymaxModelClient.unwrap(strongClient.getIfAvailable());
        // MedScribe's own default client, if there is one. `stream().findFirst()` rather than
        // `getIfAvailable()`: a context may legitimately hold more than one LlmClient (the acceptance
        // test registers a stub alongside the auto-configured one) and getIfAvailable cannot choose
        // between them, which silently handed the pipeline the wrong stub.
        LlmClient cheap = clients.stream().findFirst().orElse(null);
        // Never MedScribe's client when a Baymax vision client exists: under DR-9 the Baymax cheap tier
        // IS the vision client, and letting MedScribe's Groq client answer a Baymax document is how the
        // first haiku run silently produced a gpt-oss-120b table.
        if (vision != null) {
            cheap = vision;
        }
        boolean sendImages = properties.getExtract().isSendImages() && vision != null && vision.supportsImages();
        return new ExtractionClients(cheap, vision, strong, sendImages);
    }

    /**
     * @param cheap      the model used for every document
     * @param vision     the vision-capable cheap model, or null
     * @param strong     the escalation model, or null when none is configured
     * @param sendImages whether page images go with the prompt
     */
    public record ExtractionClients(LlmClient cheap, LlmClient vision, LlmClient strong, boolean sendImages) {

        /** The client the first extraction attempt should use. */
        public LlmClient primary() {
            LlmClient chosen = sendImages && vision != null ? vision : cheap;
            if (chosen == null) {
                throw new IllegalStateException("no LLM provider is configured; set llm.provider and its key");
            }
            return chosen;
        }
    }
}
