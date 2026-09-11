package com.elioo.healthcare.llm.provider.openai;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LlmClient} for any server that implements the OpenAI chat-completions API:
 * OpenAI itself, Groq, and compatible gateways. Only the base URL, key, model and
 * log name differ per provider.
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final String providerName;
    private final LlmProperties.OpenAiCompatible cfg;
    private final LlmProperties defaults;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final Retry retry;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    public OpenAiCompatibleLlmClient(String providerName, LlmProperties.OpenAiCompatible cfg,
                                     LlmProperties defaults, WebClient webClient, ObjectMapper mapper) {
        // Rate limits (429) on free tiers ask for 10-20 s waits: 3 attempts, 5 s base, capped at 30 s
        this(providerName, cfg, defaults, webClient, mapper, Retry.backoff(3, Duration.ofSeconds(5)).maxBackoff(Duration.ofSeconds(30)));
    }

    OpenAiCompatibleLlmClient(String providerName, LlmProperties.OpenAiCompatible cfg, LlmProperties defaults,
                              WebClient webClient, ObjectMapper mapper, RetryBackoffSpec retry) {
        this.providerName = providerName;
        this.cfg = cfg;
        this.defaults = defaults;
        this.webClient = webClient;
        this.mapper = mapper;
        this.retry = retry.filter(e -> e instanceof LlmException le && le.isRetryable())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Override
    public String providerName() {
        return providerName;
    }

    /** The JSON body of the most recent request; for tests and debugging. */
    String lastRequestBody() {
        return lastRequestBody.get();
    }

    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new LlmException(providerName + ": userPrompt is required"));
        }
        String model = request.hasCustomModelId() ? request.modelId() : cfg.getModel();
        String body = buildBody(request, model);
        lastRequestBody.set(body);
        long started = System.currentTimeMillis();
        log.info("[{}] chat/completions model={} promptChars={} json={}", providerName, model,
                request.userPrompt().length(), request.jsonOutput());
        log.debug("[{}] request body: {}", providerName, body);

        return webClient.post()
                .uri(cfg.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(text -> resp.statusCode().is2xxSuccessful()
                                ? Mono.just(text)
                                : Mono.error(toException(resp.statusCode(), text))))
                .timeout(Duration.ofSeconds(defaults.getTimeoutSeconds()))
                .retryWhen(retry)
                .map(text -> parse(text, model))
                .doOnNext(r -> log.info("[{}] done model={} in={} out={} stop={} {}ms", providerName, r.modelId(),
                        r.usage().inputTokens(), r.usage().outputTokens(), r.stopReason(),
                        System.currentTimeMillis() - started))
                .onErrorMap(e -> !(e instanceof LlmException),
                        e -> new LlmException(providerName + " call failed: " + e.getMessage(), e));
    }

    private String buildBody(LlmRequest request, String model) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : defaults.getDefaultMaxTokens());
        root.put("temperature", request.temperature() != null ? request.temperature() : defaults.getDefaultTemperature());
        if (request.topP() != null) {
            root.put("top_p", request.topP());
        }
        ArrayNode messages = root.putArray("messages");
        if (request.hasSystemPrompt()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        if (request.jsonOutput()) {
            root.putObject("response_format").put("type", "json_object");
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            request.stopSequences().forEach(stop::add);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException(providerName + ": could not serialise request", e);
        }
    }

    private LlmResponse parse(String text, String requestedModel) {
        try {
            JsonNode root = mapper.readTree(text);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText(null);
            if (content == null) {
                throw new LlmException(providerName + ": response has no choices[0].message.content");
            }
            JsonNode usage = root.path("usage");
            TokenUsage tokens = new TokenUsage(usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0));
            String model = root.path("model").asText(requestedModel);
            return new LlmResponse(content, choice.path("finish_reason").asText(null), tokens, model,
                    Map.of("provider", providerName));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(providerName + ": unreadable response: " + e.getMessage(), e);
        }
    }

    private LlmException toException(HttpStatusCode status, String body) {
        String message = body;
        try {
            JsonNode err = mapper.readTree(body).path("error").path("message");
            if (!err.isMissingNode()) {
                message = err.asText();
            }
        } catch (Exception ignored) {
            // keep the raw body as the message
        }
        return new LlmException(providerName, status.value(), providerName + " HTTP " + status.value() + ": " + message);
    }
}
