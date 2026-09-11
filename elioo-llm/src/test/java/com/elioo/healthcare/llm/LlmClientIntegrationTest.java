package com.elioo.healthcare.llm;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.SummaryOptions;
import com.elioo.healthcare.llm.health.dto.SummaryRequest;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.health.service.HealthInsightServiceImpl;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real call to the provider named by LLM_PROVIDER (groq default, or anthropic / openai).
 * Run with: RUN_LLM_INTEGRATION_TESTS=true LLM_PROVIDER=groq ./gradlew :elioo-llm:test --tests '*LlmClientIntegrationTest'
 */
@EnabledIfEnvironmentVariable(named = "RUN_LLM_INTEGRATION_TESTS", matches = "true",
        disabledReason = "Calls the real LLM provider selected by LLM_PROVIDER")
class LlmClientIntegrationTest {

    @Test
    void summaryFromConfiguredProvider() {
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "groq");
        LlmProperties p = new LlmProperties();
        ObjectMapper mapper = new ObjectMapper();
        LlmClient client;
        switch (provider) {
            case "anthropic" -> {
                p.getAnthropic().setApiKey(System.getenv("ANTHROPIC_API_KEY"));
                client = new AnthropicLlmClient(p.getAnthropic(), p);
            }
            case "openai" -> {
                p.getOpenai().setApiKey(System.getenv("OPENAI_API_KEY"));
                client = new OpenAiCompatibleLlmClient("openai", p.getOpenai(), p, WebClient.create(), mapper);
            }
            default -> {
                p.getGroq().setApiKey(System.getenv("GROQ_API_KEY"));
                client = new OpenAiCompatibleLlmClient("groq", p.getGroq(), p, WebClient.create(), mapper);
            }
        }
        HealthInsightService service = new HealthInsightServiceImpl(client, new DefaultPromptTemplateEngine(mapper), mapper);

        SummaryRequest req = new SummaryRequest(
                Map.of("HbA1c", "7.8 %", "Creatinine", "1.2 mg/dL", "LDL", "160 mg/dL"),
                null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .assertNext(s -> {
                    System.out.println("[" + provider + "] " + s.summary());
                    assertThat(s.summary()).isNotBlank();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(120));
    }
}
