package com.elioo.baymax.companion.config;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The চ্যাট's model client (DR-50 MODEL: Claude Sonnet, the tier DR-10 uses), built from {@code baymax.chat.model} and
 * {@code baymax.chat.effort}, never a model id in code. A {@code claude-*} id is an Anthropic client on the Anthropic key;
 * any other id is OpenAI-compatible on {@code llm.provider}'s key, as {@code baymaxVisionLlmClient} builds one.
 *
 * <p>A {@link BaymaxModelClient}, not an {@code LlmClient} bean, for the reason that type documents (MedScribe's default
 * client is {@code @ConditionalOnMissingBean(LlmClient.class)}). Absent when the model is blank or the key the chosen
 * branch uses is absent, the same condition shape as {@code STRONG_CLIENT}; every question then gets the fixed reply and
 * an emergency still gets the emergency lines.
 */
@Slf4j
@Configuration
public class ChatModelConfiguration {

    public static final String CHAT_CLIENT = "baymaxChatLlmClient";

    @Bean(CHAT_CLIENT)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.chat.model:}')"
            + " and ("
            + "  ('${baymax.chat.model:}'.toLowerCase().startsWith('claude-')"
            + "     ? T(org.springframework.util.StringUtils).hasText('${llm.anthropic.api-key:}')"
            + "     : ('${llm.provider:groq}'.equalsIgnoreCase('openai')"
            + "        ? T(org.springframework.util.StringUtils).hasText('${llm.openai.api-key:}')"
            + "        : T(org.springframework.util.StringUtils).hasText('${llm.groq.api-key:}')))"
            + ")")
    public BaymaxModelClient baymaxChatLlmClient(BaymaxProperties baymax, LlmProperties llm, ObjectProvider<WebClient> webClients,
                                                 ObjectProvider<ObjectMapper> mappers) {
        BaymaxProperties.Chat chat = baymax.getChat();
        String model = chat.getModel();
        if (ExtractionModelConfiguration.isAnthropicModel(model)) {
            LlmProperties.Anthropic cfg = new LlmProperties.Anthropic();
            cfg.setApiKey(llm.getAnthropic().getApiKey());
            cfg.setModel(model);
            cfg.setEffort(chat.getEffort());
            log.info("[baymax] chat client: provider=anthropic model={} effort={}", model, chat.getEffort());
            return new BaymaxModelClient(new AnthropicLlmClient(cfg, llm));
        }
        boolean openai = "openai".equalsIgnoreCase(llm.getProvider());
        LlmProperties.OpenAiCompatible source = openai ? llm.getOpenai() : llm.getGroq();
        LlmProperties.OpenAiCompatible cfg = new LlmProperties.OpenAiCompatible();
        cfg.setApiKey(source.getApiKey());
        cfg.setBaseUrl(source.getBaseUrl());
        cfg.setModel(model);
        String provider = openai ? "openai" : "groq";
        log.info("[baymax] chat client: provider={} model={} effort={}", provider, model, chat.getEffort());
        return new BaymaxModelClient(new OpenAiCompatibleLlmClient(provider, cfg, llm, webClients.getIfAvailable(WebClient::create),
                mappers.getIfUnique(ObjectMapper::new)));
    }
}
