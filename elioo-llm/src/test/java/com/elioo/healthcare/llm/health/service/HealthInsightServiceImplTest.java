package com.elioo.healthcare.llm.health.service;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.dto.RecommendationRequest;
import com.elioo.healthcare.llm.health.dto.SummaryOptions;
import com.elioo.healthcare.llm.health.dto.TargetAudience;
import com.elioo.healthcare.llm.health.dto.SummaryRequest;
import com.elioo.healthcare.llm.health.exception.HealthInsightException;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthInsightServiceImplTest {

    private LlmClient llm;
    private HealthInsightServiceImpl service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        when(llm.providerName()).thenReturn("test");
        ObjectMapper mapper = new ObjectMapper();
        service = new HealthInsightServiceImpl(llm, new DefaultPromptTemplateEngine(mapper), mapper);
    }

    @Test
    void summaryParsesFencedJsonAndRequestsJsonOutput() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple(
                "```json\n{\"summary\":\"Mostly normal results.\",\"keyPoints\":[\"HbA1c high\"]}\n```")));

        SummaryRequest req = new SummaryRequest(Map.of("HbA1c", "7.8%"), null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .assertNext(r -> assertThat(r.summary()).isEqualTo("Mostly normal results."))
                .verifyComplete();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).invoke(captor.capture());
        assertThat(captor.getValue().jsonOutput()).isTrue();
        assertThat(captor.getValue().systemPrompt()).isNotBlank();
        assertThat(captor.getValue().userPrompt()).contains("7.8%");
    }

    @Test
    void summaryOptionsWithOnlyAudienceUseDefaultsForTheRest() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("{\"summary\":\"ok\",\"keyPoints\":[]}")));

        // what an API caller typically sends: just the audience
        SummaryOptions partial = new SummaryOptions(TargetAudience.PATIENT, null, null);
        SummaryRequest req = new SummaryRequest(Map.of("HbA1c", "7.8%"), null, partial);

        StepVerifier.create(service.generateSummary(req))
                .assertNext(r -> assertThat(r.summary()).isEqualTo("ok"))
                .verifyComplete();
    }

    @Test
    void unparseableReplyBecomesHealthInsightException() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("Sorry, I cannot produce that.")));

        SummaryRequest req = new SummaryRequest(Map.of("HbA1c", "7.8%"), null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(HealthInsightException.class)
                        .hasMessageContaining("Failed to parse test reply"))
                .verify();
    }

    @Test
    void invalidRequestFailsBeforeCallingTheModel() {
        StepVerifier.create(service.generateRecommendations(new RecommendationRequest(null, null, null)))
                .expectError(HealthInsightException.class)
                .verify();
        verify(llm, never()).invoke(any());
    }

    @Test
    void customPromptUsesNoSystemPrompt() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("{\"answer\":42}")));
        record Answer(int answer) {}

        StepVerifier.create(service.executeCustomPrompt("Give me 42", Answer.class))
                .assertNext(a -> assertThat(a.answer()).isEqualTo(42))
                .verifyComplete();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).invoke(captor.capture());
        assertThat(captor.getValue().systemPrompt()).isNull();
    }
}
