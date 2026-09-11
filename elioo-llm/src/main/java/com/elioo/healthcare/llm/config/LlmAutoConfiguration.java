package com.elioo.healthcare.llm.config;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.llm.health.service.HealthInsightServiceImpl;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/**
 * Creates exactly one {@link LlmClient} according to {@code llm.provider}
 * (groq | openai | anthropic; bedrock is supplied by elioo-aws-bedrock), plus the
 * clinical prompt layer on top of it.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmAutoConfiguration {

    private static final Set<String> KNOWN_PROVIDERS = Set.of("groq", "openai", "anthropic", "bedrock");

    @Bean
    @ConditionalOnMissingBean(name = "llmWebClient")
    public WebClient llmWebClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "groq", matchIfMissing = true)
    public LlmClient groqLlmClient(LlmProperties p, WebClient llmWebClient, ObjectProvider<ObjectMapper> mappers) {
        requireKey(p.getGroq().getApiKey(), "groq", "GROQ_API_KEY");
        log.info("LLM provider: groq (model {})", p.getGroq().getModel());
        return new OpenAiCompatibleLlmClient("groq", p.getGroq(), p, llmWebClient, mapper(mappers));
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
    public LlmClient openaiLlmClient(LlmProperties p, WebClient llmWebClient, ObjectProvider<ObjectMapper> mappers) {
        requireKey(p.getOpenai().getApiKey(), "openai", "OPENAI_API_KEY");
        log.info("LLM provider: openai (model {})", p.getOpenai().getModel());
        return new OpenAiCompatibleLlmClient("openai", p.getOpenai(), p, llmWebClient, mapper(mappers));
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
    public LlmClient anthropicLlmClient(LlmProperties p) {
        requireKey(p.getAnthropic().getApiKey(), "anthropic", "ANTHROPIC_API_KEY");
        log.info("LLM provider: anthropic (model {}, effort {})", p.getAnthropic().getModel(), p.getAnthropic().getEffort());
        return new AnthropicLlmClient(p.getAnthropic(), p);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateEngine promptTemplateEngine(ObjectProvider<ObjectMapper> mappers) {
        return new DefaultPromptTemplateEngine(mapper(mappers));
    }

    /** Requires an LlmClient; with llm.provider=bedrock that bean comes from elioo-aws-bedrock. */
    @Bean
    @ConditionalOnMissingBean
    public HealthInsightService healthInsightService(LlmClient llmClient, PromptTemplateEngine engine,
                                                     ObjectProvider<ObjectMapper> mappers, LlmProperties p) {
        if (!KNOWN_PROVIDERS.contains(p.getProvider())) {
            throw new LlmException("Unknown llm.provider '" + p.getProvider()
                    + "'; expected groq, openai, anthropic or bedrock");
        }
        return new HealthInsightServiceImpl(llmClient, engine, mapper(mappers));
    }

    /** Reuse the application's ObjectMapper when there is one (Spring Boot Jackson), else a private instance. */
    private static ObjectMapper mapper(ObjectProvider<ObjectMapper> mappers) {
        return mappers.getIfUnique(ObjectMapper::new);
    }

    private static void requireKey(String key, String provider, String envVar) {
        if (key == null || key.isBlank()) {
            throw new LlmException("llm.provider=" + provider + " but " + envVar + " is not set");
        }
    }
}
