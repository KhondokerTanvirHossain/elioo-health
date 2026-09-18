package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The composition pipeline with every port mocked: skeleton, model, checklist, gate, delivery. */
class ExplanationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final UUID DOC = UUID.randomUUID(), PATIENT = UUID.randomUUID(), FAMILY = UUID.randomUUID();

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final OutboundMessagePort messages = mock(OutboundMessagePort.class);
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final LlmClient sonnet = mock(LlmClient.class);
    private final ReviewerNotificationPort reviewer = mock(ReviewerNotificationPort.class);
    private final MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final Deque<String> replies = new ArrayDeque<>();
    private ExplanationService service;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ExplanationService(documents, records, messages, metered,
                new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new UrgencyService(properties, clock), new MessageSafetyCheck(properties), new OutboundMessageGate(properties),
                reviewer, delivery, new Copy(), properties, clock);
        when(metered.using(any())).thenReturn(metered);
        when(metered.invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class))).thenAnswer(i -> Mono.just(
                new LlmResponse(replies.isEmpty() ? "" : replies.pop(), "stop", new TokenUsage(500, 120), "claude-sonnet-5", null, "anthropic", 900L)));
        when(messages.save(any())).thenAnswer(i -> {
            OutboundMessage m = i.getArgument(0);
            return Mono.just(new OutboundMessage(UUID.randomUUID(), m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                    m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), m.sentAt(), m.createdAt()));
        });
        when(reviewer.notifyPending(any())).thenReturn(Mono.empty());
        when(delivery.deliver(any())).thenReturn(Mono.empty());
        when(records.findPatient(PATIENT)).thenReturn(Mono.just(new PatientProfile(PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)));
        when(documents.followUpsOf(DOC)).thenReturn(Flux.empty());
        when(messages.consecutiveRetakes(FAMILY)).thenReturn(Mono.just(0L));
        when(documents.clinicalContextOf(DOC)).thenReturn(Mono.empty());
    }

    private void done(Map<String, Object>... values) {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-09-14"), "Popular",
                "{}", 0.93, Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, NOW, NOW)));
        when(documents.observationsOf(DOC)).thenReturn(Flux.fromArray(values));
    }

    private static final Map<String, Object> CRITICAL = UrgencyServiceTest.value("HbA1c", "11.2", "4.0", "5.6");
    private static final Map<String, Object> NORMAL = UrgencyServiceTest.value("HbA1c", "5.0", "4.0", "5.6");

    /** Acceptance: a body with a number absent from the extraction is rejected, regenerated once, then fails closed and is logged. */
    @Test
    void aBodyWithAForeignNumberIsRegeneratedOnceThenFailsClosed() {
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2 mmol। এখনই ডাক্তারের কাছে যান। বাকি সব 77।");
        replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। আরও 42 দিন।");

        StepVerifier.create(service.explain(DOC))
                .assertNext(m -> {
                    assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
                    assertThat(m.body()).isNull();
                    assertThat(m.urgency()).isEqualTo(Urgency.NOW);
                })
                .verifyComplete();
        verify(metered, times(2)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        verify(delivery, never()).deliver(any());
        verify(reviewer, never()).notifyPending(any());
    }

    /** Acceptance: gate mode=urgency and a NOW message → nothing sends until approve. */
    @Test
    void aNowMessageUnderTheUrgencyGateIsParkedNotSent() {
        properties.getOutbound().setGateMode("urgency");
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না। সাথে নিয়ে যাবেন: এই রিপোর্ট।");

        StepVerifier.create(service.explain(DOC))
                .assertNext(m -> {
                    assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
                    assertThat(m.urgency()).isEqualTo(Urgency.NOW);
                    assertThat(m.sentAt()).isNull();
                })
                .verifyComplete();
        verify(reviewer).notifyPending(any());
        verify(delivery, never()).deliver(any());
    }

    /** Acceptance: given a critical value, urgency=NOW and the stored row carries exactly that. */
    @Test
    void aCriticalValueIsNowAndTheStoredRowCarriesIt() {
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");
        StepVerifier.create(service.explain(DOC)).assertNext(m -> {
            assertThat(m.urgency()).isEqualTo(Urgency.NOW);
            assertThat(m.urgencyReasons()).contains("value_critical:hba1c");
            assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.RELEASED);
            assertThat(m.body()).contains("এখনই ডাক্তারের কাছে যান");
        }).verifyComplete();
        verify(delivery).deliver(any());
    }

    /** ROUTINE with nothing standing out: the template is the message; no model call, no cost. */
    @Test
    void aRoutineDocumentIsTheTemplateWithoutAModelCall() {
        done(NORMAL);
        StepVerifier.create(service.explain(DOC)).assertNext(m -> {
            assertThat(m.urgency()).isEqualTo(Urgency.ROUTINE);
            assertThat(m.body()).contains("সব কিছু স্বাভাবিক সীমার মধ্যে আছে").contains("ল্যাব রিপোর্ট").contains("2026-09-14").contains("Ma");
            assertThat(m.body().length()).isLessThanOrEqualTo(600);
        }).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }

    /** Acceptance: NEEDS_RETAKE → the retake prompt only; no facts read, no model. */
    @Test
    void aRetakeDocumentGetsTheRetakePromptAndNothingElse() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, NOW, NOW)));
        StepVerifier.create(service.explain(DOC)).assertNext(m -> {
            assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.RETAKE);
            assertThat(m.body()).contains("ছবিটা স্পষ্ট বোঝা যাচ্ছে না");
            assertThat(m.urgency()).isEqualTo(Urgency.ROUTINE);
        }).verifyComplete();
        verify(documents, never()).observationsOf(any());
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }

    @Test
    void medicinesNeverReachTheSkeletonOrTheModel() {
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");
        service.explain(DOC).block();
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(metered).invoke(eq(AiCallPurpose.EXPLAIN), any(), req.capture());
        assertThat(req.getValue().userPrompt()).doesNotContainIgnoringCase("tab.").doesNotContain("medicine");
        verify(documents, never()).medicinesOf(any());
    }

    /** Consecutive retakes escalate the copy: specific on the second, help instead of a third attempt on the third. */
    @Test
    void consecutiveRetakesGetMoreSpecificThenOfferHelp() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, NOW, NOW)));
        when(messages.consecutiveRetakes(FAMILY)).thenReturn(Mono.just(1L));
        StepVerifier.create(service.explain(DOC)).assertNext(m -> {
            assertThat(m.body()).contains("তিনটা জিনিস চেষ্টা করুন");
            assertThat(m.urgencyReasons()).containsExactly("retake_2");
        }).verifyComplete();
        when(messages.consecutiveRetakes(FAMILY)).thenReturn(Mono.just(2L));
        StepVerifier.create(service.explain(DOC)).assertNext(m -> {
            assertThat(m.body()).contains("এটা আপনার দোষ নয়").contains("অন্য কোনো রিপোর্ট");
            // No human offer until BMX-10 routes the reply; a promise into silence is worse than none (PO).
            assertThat(m.body()).doesNotContain("সাহায্য").doesNotContain("একজন মানুষ");
            assertThat(m.body().length()).isLessThanOrEqualTo(600);
        }).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }
}
