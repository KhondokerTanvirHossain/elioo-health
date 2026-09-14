package com.elioo.baymax.extraction.config;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
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

    /**
     * A Groq (or any OpenAI-compatible) client pinned to the configured vision model. Absent when no
     * vision model is configured or the provider has no key, in which case extraction runs text-only.
     */
    @Bean(VISION_CLIENT)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.extract.vision-model:}')")
    public LlmClient baymaxVisionLlmClient(BaymaxProperties baymax, LlmProperties llm,
                                           ObjectProvider<WebClient> webClients,
                                           ObjectProvider<ObjectMapper> mappers) {
        String model = baymax.getExtract().getVisionModel();
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
        return new OpenAiCompatibleLlmClient(provider, vision, llm,
                webClients.getIfAvailable(WebClient::create), mappers.getIfUnique(ObjectMapper::new));
    }

    /**
     * The clients extraction will use. Both are optional so that a context without an LLM provider (a
     * sliced test, or an install with no key) still starts; a call then fails at call time with a clear
     * message rather than preventing the module from loading.
     */
    @Bean
    public ExtractionClients extractionClients(ObjectProvider<LlmClient> clients,
                                               @Qualifier(VISION_CLIENT) ObjectProvider<LlmClient> visionClient,
                                               BaymaxProperties properties) {
        LlmClient vision = visionClient.getIfAvailable();
        LlmClient cheap = clients.stream().filter(c -> c != vision).findFirst().orElse(vision);
        boolean sendImages = properties.getExtract().isSendImages() && vision != null && vision.supportsImages();
        return new ExtractionClients(cheap, vision, sendImages);
    }

    /**
     * @param cheap      the model used for every document
     * @param vision     the vision-capable cheap model, or null
     * @param sendImages whether page images go with the prompt
     */
    public record ExtractionClients(LlmClient cheap, LlmClient vision, boolean sendImages) {

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
