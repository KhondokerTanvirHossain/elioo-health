package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * The only way Baymax code talks to a text model. Every successful call writes one
 * {@code ai_call_log} row with provider, model, tokens, computed cost, latency and, when the caller can
 * read one from the reply, a confidence. Prompt and reply text never reach the log.
 *
 * <p>Failed calls are not logged as rows (there are no tokens or cost to record); they are logged at WARN
 * with purpose, provider and latency. A failure to write the row is logged at ERROR and does not fail
 * the call: the answer already exists, and losing one cost row is the lesser harm.</p>
 *
 * <p>Wraps the application's default {@link LlmClient}. For the strong-model tier (DR-3) call
 * {@link #using(LlmClient)} with the other client; the metering is identical.</p>
 */
@Slf4j
@Component
public class MeteredLlmClient {

    private final LlmClient llmClient;
    private final AiCallLogPort callLog;
    private final AiCostCalculator costs;

    public MeteredLlmClient(LlmClient llmClient, AiCallLogPort callLog, AiCostCalculator costs) {
        this.llmClient = llmClient;
        this.callLog = callLog;
        this.costs = costs;
    }

    /** Same metering, different model client. */
    public MeteredLlmClient using(LlmClient other) {
        return new MeteredLlmClient(other, callLog, costs);
    }

    public Mono<LlmResponse> invoke(AiCallPurpose purpose, UUID documentId, LlmRequest request) {
        return invoke(purpose, documentId, request, response -> null);
    }

    /**
     * @param confidenceOf reads a 0..1 confidence out of the reply (e.g. the extraction's "overall"),
     *                     or returns null when the reply carries none; exceptions are treated as null
     */
    public Mono<LlmResponse> invoke(AiCallPurpose purpose, UUID documentId, LlmRequest request,
                                    Function<LlmResponse, Double> confidenceOf) {
        long started = System.currentTimeMillis();
        return llmClient.invoke(request)
                .flatMap(response -> record(purpose, documentId, response, started, confidenceOf)
                        .thenReturn(response))
                .doOnError(e -> log.warn("[baymax] llm call failed purpose={} documentId={} provider={} after {}ms: {}",
                        purpose.dbValue(), documentId, llmClient.providerName(),
                        System.currentTimeMillis() - started, e.getMessage()));
    }

    private Mono<AiCallRecord> record(AiCallPurpose purpose, UUID documentId, LlmResponse response,
                                      long started, Function<LlmResponse, Double> confidenceOf) {
        TokenUsage usage = response.hasUsage() ? response.usage() : TokenUsage.empty();
        String provider = response.provider() != null ? response.provider() : llmClient.providerName();
        String model = response.modelId() != null ? response.modelId() : "unknown";
        long latency = response.latencyMs() != null ? response.latencyMs() : System.currentTimeMillis() - started;
        BigDecimal cost = costs.llmCost(provider, model, usage.inputTokens(), usage.outputTokens()).orElse(null);
        Double confidence = confidence(confidenceOf, response);

        AiCallRecord record = new AiCallRecord(null, documentId, purpose, provider, model,
                usage.inputTokens(), usage.outputTokens(), cost, latency, confidence, Instant.now());
        return callLog.save(record)
                .doOnNext(saved -> log.info("[baymax] ai_call purpose={} provider={} model={} in={} out={} cost={} {}ms confidence={} documentId={}",
                        purpose.dbValue(), provider, model, usage.inputTokens(), usage.outputTokens(),
                        cost, latency, confidence, documentId))
                .onErrorResume(e -> {
                    log.error("[baymax] could not write ai_call_log row purpose={} provider={} model={} documentId={}: {}",
                            purpose.dbValue(), provider, model, documentId, e.getMessage(), e);
                    return Mono.just(record);
                });
    }

    private static Double confidence(Function<LlmResponse, Double> confidenceOf, LlmResponse response) {
        try {
            return confidenceOf.apply(response);
        } catch (RuntimeException e) {
            log.debug("[baymax] confidence extractor failed: {}", e.getMessage());
            return null;
        }
    }
}
