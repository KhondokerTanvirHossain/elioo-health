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
 * The client of the চ্যাট's refuse-only model check (PO ruling P15, {@code ChatAnswerCheck}), built from
 * {@code baymax.chat.check.model}, never a model id in code: a {@code claude-*} id is an Anthropic client on the Anthropic
 * key, any other id OpenAI-compatible on {@code llm.provider}'s key, exactly as {@link ChatModelConfiguration} builds the chat's
 * own client. Absent when the model is blank or that key is absent; the check then refuses every answer it would have read
 * (fail closed), so an answer about a medicine is never shown unchecked because a key went missing.
 *
 * <p>The Anthropic client gets the check's own effort ({@code baymax.chat.check.effort}, low): Haiku 5.5 thinks adaptively at
 * {@code medium} unless told otherwise, and a one-line verdict needs no more than {@code low} (review of #173, findings 7 and
 * 10). {@code AnthropicLlmClient} sends the effort to every model but Haiku 4.x and the Claude 3 Haikus.
 */
@Slf4j
@Configuration
public class ChatCheckModelConfiguration {

    public static final String CHECK_CLIENT = "baymaxChatCheckLlmClient";

    @Bean(CHECK_CLIENT)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.chat.check.model:}')"
            + " and ("
            + "  ('${baymax.chat.check.model:}'.toLowerCase().startsWith('claude-')"
            + "     ? T(org.springframework.util.StringUtils).hasText('${llm.anthropic.api-key:}')"
            + "     : ('${llm.provider:groq}'.equalsIgnoreCase('openai')"
            + "        ? T(org.springframework.util.StringUtils).hasText('${llm.openai.api-key:}')"
            + "        : T(org.springframework.util.StringUtils).hasText('${llm.groq.api-key:}')))"
            + ")")
    public BaymaxModelClient baymaxChatCheckLlmClient(BaymaxProperties baymax, LlmProperties llm, ObjectProvider<WebClient> webClients,
                                                      ObjectProvider<ObjectMapper> mappers) {
        String model = baymax.getChat().getCheck().getModel();
        if (ExtractionModelConfiguration.isAnthropicModel(model)) {
            LlmProperties.Anthropic cfg = new LlmProperties.Anthropic();
            cfg.setApiKey(llm.getAnthropic().getApiKey());
            cfg.setModel(model);
            cfg.setEffort(baymax.getChat().getCheck().getEffort());
            log.info("[baymax] chat check client: provider=anthropic model={} effort={}", model, cfg.getEffort());
            return new BaymaxModelClient(new AnthropicLlmClient(cfg, llm));
        }
        boolean openai = "openai".equalsIgnoreCase(llm.getProvider());
        LlmProperties.OpenAiCompatible source = openai ? llm.getOpenai() : llm.getGroq();
        LlmProperties.OpenAiCompatible cfg = new LlmProperties.OpenAiCompatible();
        cfg.setApiKey(source.getApiKey());
        cfg.setBaseUrl(source.getBaseUrl());
        cfg.setModel(model);
        String provider = openai ? "openai" : "groq";
        log.info("[baymax] chat check client: provider={} model={}", provider, model);
        return new BaymaxModelClient(new OpenAiCompatibleLlmClient(provider, cfg, llm, webClients.getIfAvailable(WebClient::create),
                mappers.getIfUnique(ObjectMapper::new)));
    }
}
