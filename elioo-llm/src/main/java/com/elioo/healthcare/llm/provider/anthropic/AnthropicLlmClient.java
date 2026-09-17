package com.elioo.healthcare.llm.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
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

import java.util.ArrayList;
import java.util.List;
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
    public boolean supportsImages() {
        return true;
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
                request.userPrompt().length(),
                supportsAdaptiveThinking(params.model().toString()) ? cfg.getEffort() : "n/a");
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
        String model = request.hasCustomModelId() ? request.modelId() : cfg.getModel();
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens() != null ? request.maxTokens().longValue() : defaults.getDefaultMaxTokens());
        // Not every model takes these. Haiku 4.5 rejects both outright — "adaptive thinking is not
        // supported on this model" / "This model does not support the effort parameter" — so sending
        // them unconditionally turns a working cheap tier into a 400 on every call. Verified against
        // the API, 2026-09-17. Extraction is transcription, so the cheap tier simply goes without.
        if (supportsAdaptiveThinking(model)) {
            b.thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(effort(cfg.getEffort())).build());
        }
        if (request.hasImages()) {
            // Images first, then the prompt: Anthropic recommends this order for document questions.
            List<ContentBlockParam> blocks = new ArrayList<>();
            request.images().forEach(image -> blocks.add(ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                            .source(Base64ImageSource.builder()
                                    .data(image.base64())
                                    .mediaType(mediaType(image.mediaType()))
                                    .build())
                            .build())));
            blocks.add(ContentBlockParam.ofText(request.userPrompt()));
            b.addUserMessageOfBlockParams(blocks);
        } else {
            b.addUserMessage(request.userPrompt());
        }
        if (request.hasSystemPrompt()) {
            b.system(request.systemPrompt());
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            b.stopSequences(request.stopSequences());
        }
        // temperature / top_p deliberately not sent: rejected by Claude Opus 5
        return b.build();
    }

    private static Base64ImageSource.MediaType mediaType(String ianaType) {
        return switch (ianaType == null ? "" : ianaType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
    }

    /**
     * Whether the model takes {@code thinking: adaptive} and {@code output_config.effort}.
     *
     * <p>A denylist, not an allowlist: an unknown model id is assumed modern and gets the parameters,
     * so a model released after this code was written is not silently downgraded. The cost of being
     * wrong in that direction is a clear 400 at the first call; the cost of an allowlist is a new model
     * quietly losing thinking with nothing in the logs to say so.
     *
     * <p>Checked against the Models API on 2026-09-17: Haiku 4.5 reports {@code effort.supported=false}
     * and {@code thinking.types.adaptive.supported=false}.
     */
    static boolean supportsAdaptiveThinking(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        return !id.contains("haiku");
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
