package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeteredLlmClientTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final AiCallLogPort port = mock(AiCallLogPort.class);
    private final UUID documentId = UUID.randomUUID();
    private MeteredLlmClient metered;

    @BeforeEach
    void setUp() {
        BaymaxProperties props = new BaymaxProperties();
        BaymaxProperties.Price price = new BaymaxProperties.Price();
        price.setInput(new BigDecimal("0.15"));
        price.setOutput(new BigDecimal("0.60"));
        props.getLlm().getPrices().put("groq", Map.of("openai/gpt-oss-120b", price));
        when(llm.providerName()).thenReturn("groq");
        when(port.save(any())).thenAnswer(inv -> Mono.just(((AiCallRecord) inv.getArgument(0)).withId(UUID.randomUUID())));
        metered = new MeteredLlmClient(llm, port, new AiCostCalculator(props));
    }

    private static LlmResponse reply(String content, String model, String provider, long latency) {
        return new LlmResponse(content, "stop", new TokenUsage(1000, 200), model, null, provider, latency);
    }

    @Test
    void successfulCallWritesOneRowWithTokensCostLatencyAndConfidence() {
        when(llm.invoke(any())).thenReturn(Mono.just(reply("{\"confidence\":{\"overall\":0.91}}",
                "openai/gpt-oss-120b", "groq", 321)));

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x"), r -> 0.91))
                .expectNextMatches(r -> r.content().contains("0.91"))
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        AiCallRecord row = saved.getValue();
        assertThat(row.documentId()).isEqualTo(documentId);
        assertThat(row.purpose()).isEqualTo(AiCallPurpose.EXTRACT);
        assertThat(row.provider()).isEqualTo("groq");
        assertThat(row.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(row.inputTokens()).isEqualTo(1000);
        assertThat(row.outputTokens()).isEqualTo(200);
        assertThat(row.costUsd()).isEqualByComparingTo("0.00027");
        assertThat(row.latencyMs()).isEqualTo(321);
        assertThat(row.confidence()).isEqualTo(0.91);
        assertThat(row.createdAt()).isNotNull();
    }

    @Test
    void unknownModelIsLoggedWithNullCost() {
        when(llm.invoke(any())).thenReturn(Mono.just(reply("ok", "llama-3.3-70b-versatile", "groq", 10)));

        StepVerifier.create(metered.invoke(AiCallPurpose.CHAT, null, LlmRequest.standard("x"))).expectNextCount(1).verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().costUsd()).isNull();
        assertThat(saved.getValue().inputTokens()).isEqualTo(1000);
        assertThat(saved.getValue().documentId()).isNull();
        assertThat(saved.getValue().confidence()).isNull();
    }

    @Test
    void fallsBackToClientProviderAndMeasuredLatencyWhenResponseLacksThem() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.withUsage("ok", new TokenUsage(5, 5))));

        StepVerifier.create(metered.invoke(AiCallPurpose.EXPLAIN, documentId, LlmRequest.standard("x"))).expectNextCount(1).verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().provider()).isEqualTo("groq");
        assertThat(saved.getValue().model()).isEqualTo("unknown");
        assertThat(saved.getValue().latencyMs()).isNotNull();
    }

    @Test
    void failedCallWritesAFailedRowAndPropagatesTheError() {
        // BMX-2 changed this: a failed call used to write nothing, and now writes a row with status=failed,
        // zero tokens and no cost, so an unhealthy provider is visible in the cost log.
        when(llm.invoke(any())).thenReturn(Mono.error(new LlmException("groq HTTP 429")));

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x")))
                .expectError(LlmException.class)
                .verify();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(AiCallRecord.Status.FAILED);
        assertThat(saved.getValue().costUsd()).isNull();
        assertThat(saved.getValue().inputTokens()).isZero();
    }

    @Test
    void confidenceExtractorErrorsBecomeNullNotFailures() {
        when(llm.invoke(any())).thenReturn(Mono.just(reply("not json", "openai/gpt-oss-120b", "groq", 1)));

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x"),
                        r -> { throw new IllegalArgumentException("no confidence"); }))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().confidence()).isNull();
    }

    @Test
    void logWriteFailureDoesNotFailTheCall() {
        when(llm.invoke(any())).thenReturn(Mono.just(reply("ok", "openai/gpt-oss-120b", "groq", 1)));
        doReturn(Mono.error(new IllegalStateException("db down"))).when(port).save(any());

        StepVerifier.create(metered.invoke(AiCallPurpose.CHAT, documentId, LlmRequest.standard("x")))
                .expectNextMatches(r -> "ok".equals(r.content()))
                .verifyComplete();
    }

    @Test
    void usingAnotherClientKeepsTheMetering() {
        LlmClient strong = mock(LlmClient.class);
        when(strong.providerName()).thenReturn("anthropic");
        when(strong.invoke(any())).thenReturn(Mono.just(reply("ok", "claude-opus-5", "anthropic", 900)));

        StepVerifier.create(metered.using(strong).invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x")))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().provider()).isEqualTo("anthropic");
        assertThat(saved.getValue().costUsd()).as("no anthropic price configured in this test").isNull();
        verify(llm, never()).invoke(any());
    }
}
