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

    /** what save() last returned, so find() can hand the released row back (V13 path) */
    private final java.util.concurrent.atomic.AtomicReference<OutboundMessage> lastSaved =
            new java.util.concurrent.atomic.AtomicReference<>();

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
            lastSaved.set(new OutboundMessage(UUID.randomUUID(), m.familyId(), m.patientId(), m.documentId(), m.kind(),
                    m.urgency(), m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(),
                    m.decidedAt(), m.sentAt(), m.createdAt()));
            return Mono.just(new OutboundMessage(lastSaved.get().id(), m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                    m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), m.sentAt(), m.createdAt()));
        });
        when(reviewer.notifyPending(any())).thenReturn(Mono.empty());
        // the log adapter's outcome; the released path then stamps sent_at and re-reads the row
        when(delivery.deliver(any())).thenReturn(Mono.just(com.elioo.baymax.outbound.domain.DeliveryOutcome.logged()));
        when(messages.markSent(any(), any())).thenReturn(Mono.empty());
        when(messages.find(any())).thenAnswer(i -> Mono.justOrEmpty(lastSaved.get()));
        when(records.findPatient(PATIENT)).thenReturn(Mono.just(new PatientProfile(PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)));
        when(documents.followUpsOf(DOC)).thenReturn(Flux.empty());
        when(messages.consecutiveRetakes(FAMILY)).thenReturn(Mono.just(0L));
        when(documents.clinicalContextOf(DOC)).thenReturn(Mono.empty());
        when(documents.medicinesOf(DOC)).thenReturn(Flux.empty());
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
            assertThat(m.urgencyReasons()).anySatisfy(r -> assertThat(r).startsWith("value_critical:hba1c"));
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
            assertThat(m.body()).contains("সব কিছু স্বাভাবিক সীমার মধ্যে আছে").contains("ল্যাব রিপোর্ট").contains("১৪ সেপ্টেম্বর").contains("Ma");
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

    private static final Map<String, Object> NAPA = Map.of("name", "Tab. Napa", "dose_text", "500 mg", "frequency_text", "১+০+১",
            "timing_text", "after meal", "crop_key", "f/p/d/crop-m1.jpg");
    private static final Map<String, Object> LOSARTAN = Map.of("name", "Losartan", "dose_text", "50mg", "frequency_text", "0+0+1",
            "crop_key", "f/p/d/crop-m2.jpg");

    /** DR-16: medicines never reach the model; they are appended verbatim after generation, character for character. */
    @Test
    void medicinesNeverReachTheModelAndAreAppendedVerbatim() {
        done(CRITICAL);
        when(documents.medicinesOf(DOC)).thenReturn(Flux.just(NAPA, LOSARTAN));
        replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");
        OutboundMessage m = service.explain(DOC).block();
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(metered).invoke(eq(AiCallPurpose.EXPLAIN), any(), req.capture());
        assertThat(req.getValue().userPrompt()).doesNotContain("Napa").doesNotContain("Losartan").doesNotContain("500 mg").doesNotContain("medicine");
        assertThat(req.getValue().systemPrompt()).doesNotContain("Napa");
        // the body: the model's text first, then the fixed header and one verbatim line per medicine, " · " between fields
        assertThat(m.body()).startsWith("HbA1c এসেছে 11.2।")
                .contains("প্রেসক্রিপশনে যা লেখা আছে:\nTab. Napa · 500 mg · ১+০+১ · after meal\nLosartan · 50mg · 0+0+1");
        assertThat(MedicineTranscription.verify(m.body(), List.of(NAPA, LOSARTAN))).isEmpty();
        // "mg", "tab." and "500" would trip the phrase and number checks on model text; the verbatim block is exempt
        assertThat(m.gateStatus()).isNotEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
    }

    /** DR-16: the same block on the ROUTINE template (no model call) and on the detail message. */
    @Test
    void theVerbatimBlockIsOnTemplateOnlyAndDetailMessagesToo() {
        done(NORMAL);
        when(documents.medicinesOf(DOC)).thenReturn(Flux.just(NAPA));
        OutboundMessage routine = service.explain(DOC).block();
        assertThat(routine.body()).endsWith("প্রেসক্রিপশনে যা লেখা আছে:\nTab. Napa · 500 mg · ১+০+১ · after meal");
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        replies.push("HbA1c 5.0, স্বাভাবিক 4.0–5.6।");   // no link: a link with foreign digits would fail the number check, correctly
        OutboundMessage detail = service.detail(DOC).block();
        assertThat(detail.body()).contains("Tab. Napa · 500 mg · ১+০+১ · after meal");
    }

    /** DR-16 assertion: a stored medicine string that is not in the body character for character is a failure. */
    @Test
    void aMedicineStringNotVerbatimIsCaught() {
        assertThat(MedicineTranscription.verify("Tab. Napa · 500mg · ১+০+১", List.of(NAPA))).containsExactly("dose_text:500 mg", "timing_text:after meal");
        assertThat(MedicineTranscription.verify("x\nTab. Napa · 500 mg · ১+০+১ · after meal", List.of(NAPA))).isEmpty();
        assertThat(MedicineTranscription.block("H:", List.of())).isEmpty();
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
