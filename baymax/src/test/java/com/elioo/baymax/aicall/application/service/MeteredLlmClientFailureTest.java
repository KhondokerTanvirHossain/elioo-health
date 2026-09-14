package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BMX-2 carry-over: a failed provider call is logged with zero tokens, no cost and its real latency. */
class MeteredLlmClientFailureTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final AiCallLogPort port = mock(AiCallLogPort.class);
    private final UUID documentId = UUID.randomUUID();
    private MeteredLlmClient metered;

    @BeforeEach
    void setUp() {
        when(llm.providerName()).thenReturn("groq");
        when(port.save(any())).thenAnswer(i -> i.getArgument(0) == null ? Mono.empty()
                : Mono.just(((AiCallRecord) i.getArgument(0)).withId(UUID.randomUUID())));
        metered = new MeteredLlmClient(llm, port, new AiCostCalculator(new BaymaxProperties()));
    }

    @Test
    void aFailedCallWritesAFailedRowAndStillPropagatesTheError() {
        when(llm.invoke(any())).thenReturn(Mono.error(new LlmException("groq HTTP 503")));

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x")))
                .expectError(LlmException.class)
                .verify();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        AiCallRecord row = saved.getValue();
        assertThat(row.status()).isEqualTo(AiCallRecord.Status.FAILED);
        assertThat(row.inputTokens()).isZero();
        assertThat(row.outputTokens()).isZero();
        assertThat(row.costUsd()).isNull();
        assertThat(row.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.documentId()).isEqualTo(documentId);
        assertThat(row.purpose()).isEqualTo(AiCallPurpose.EXTRACT);
        assertThat(row.provider()).isEqualTo("groq");
    }

    @Test
    void theModelNameOnAFailedRowIsTheOneWeAskedFor() {
        when(llm.invoke(any())).thenReturn(Mono.error(new LlmException("boom")));
        LlmRequest request = new LlmRequest("x", null, "claude-opus-5", null, null, null, null, null, true);

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, request))
                .expectError(LlmException.class)
                .verify();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().model()).isEqualTo("claude-opus-5");
    }

    @Test
    void aSuccessfulCallIsStillRecordedAsOk() {
        when(llm.invoke(any())).thenReturn(Mono.just(
                com.elioo.healthcare.llm.model.LlmResponse.withUsage("ok",
                        new com.elioo.healthcare.llm.model.TokenUsage(10, 5))));

        StepVerifier.create(metered.invoke(AiCallPurpose.CHAT, documentId, LlmRequest.standard("x")))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AiCallRecord> saved = ArgumentCaptor.forClass(AiCallRecord.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(AiCallRecord.Status.OK);
    }

    @Test
    void failingToWriteTheFailureRowDoesNotReplaceTheOriginalError() {
        when(llm.invoke(any())).thenReturn(Mono.error(new LlmException("groq HTTP 429")));
        doReturn(Mono.error(new IllegalStateException("db down"))).when(port).save(any());

        StepVerifier.create(metered.invoke(AiCallPurpose.EXTRACT, documentId, LlmRequest.standard("x")))
                .expectErrorMatches(e -> e instanceof LlmException && e.getMessage().contains("429"))
                .verify();
    }
}
