package com.elioo.healthcare.llm.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link LlmClient} on the official Anthropic Java SDK. */
@Slf4j
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final LlmProperties.Anthropic cfg;
    private final LlmProperties defaults;

    public AnthropicLlmClient(LlmProperties.Anthropic cfg, LlmProperties defaults) {
        this(AnthropicOkHttpClient.builder()
                        .apiKey(cfg.getApiKey())
                        .timeout(Duration.ofSeconds(defaults.getTimeoutSeconds()))
                        .maxRetries(2)
                        .build(),
                cfg, defaults);
    }

    AnthropicLlmClient(AnthropicClient client, LlmProperties.Anthropic cfg, LlmProperties defaults) {
        this.client = client;
        this.cfg = cfg;
        this.defaults = defaults;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new LlmException("anthropic: userPrompt is required"));
        }
        MessageCreateParams params = buildParams(request);
        long started = System.currentTimeMillis();
        log.info("[anthropic] messages.create model={} promptChars={} effort={}", params.model(),
                request.userPrompt().length(), cfg.getEffort());
        log.debug("[anthropic] user prompt: {}", request.userPrompt());

        return Mono.fromCallable(() -> client.messages().create(params))
                .subscribeOn(Schedulers.boundedElastic())
                .map(message -> toResponse(message).timed("anthropic", System.currentTimeMillis() - started))
                .doOnNext(r -> log.info("[anthropic] done model={} in={} out={} stop={} {}ms", r.modelId(),
                        r.usage().inputTokens(), r.usage().outputTokens(), r.stopReason(),
                        System.currentTimeMillis() - started))
                .onErrorMap(AnthropicServiceException.class, e -> new LlmException("anthropic", e.statusCode(),
                        "anthropic HTTP " + e.statusCode() + ": " + e.getMessage()))
                .onErrorMap(e -> !(e instanceof LlmException),
                        e -> new LlmException("anthropic call failed: " + e.getMessage(), e));
    }

    MessageCreateParams buildParams(LlmRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(request.hasCustomModelId() ? request.modelId() : cfg.getModel())
                .maxTokens(request.maxTokens() != null ? request.maxTokens().longValue() : defaults.getDefaultMaxTokens())
                .addUserMessage(request.userPrompt())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort(cfg.getEffort())).build());
        if (request.hasSystemPrompt()) {
            b.system(request.systemPrompt());
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            b.stopSequences(request.stopSequences());
        }
        // temperature / top_p deliberately not sent: rejected by Claude Opus 5
        return b.build();
    }

    private static OutputConfig.Effort effort(String value) {
        return switch (value == null ? "medium" : value.toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    private static LlmResponse toResponse(Message message) {
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        String stop = message.stopReason().map(Object::toString).orElse(null);
        return toResponse(text, stop, message.usage().inputTokens(), message.usage().outputTokens(),
                message.model().toString());
    }

    static LlmResponse toResponse(String text, String stopReason, long inputTokens, long outputTokens, String model) {
        if ("refusal".equalsIgnoreCase(stopReason)) {
            throw new LlmException("anthropic refused the request (stop_reason=refusal)");
        }
        return new LlmResponse(text, stopReason, new TokenUsage((int) inputTokens, (int) outputTokens), model,
                Map.of("provider", "anthropic"));
    }
}
