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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    /** F1: the stored urgency, with the adapter's merge semantics; ExplanationService composes at what it holds. */
    private final InMemoryDocumentUrgency stored = new InMemoryDocumentUrgency();
    private ExplanationService service;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ExplanationService(documents, records, messages, metered,
                new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new MessageSafetyCheck(properties), new OutboundMessageGate(properties),
                reviewer, delivery, new Copy(), properties, clock,
                new StoredUrgencyService(new UrgencyService(properties, clock), stored, documents, records, properties, clock));
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
        // F4: no cached DETAIL unless a test says so; a claim succeeds on the row save() last returned
        when(messages.latestOfKind(any(), any())).thenReturn(Mono.empty());
        when(messages.claimDelivery(any())).thenAnswer(i -> messages.find(i.getArgument(0)));
    }

    private void done(Map<String, Object>... values) {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-09-14"), "Popular",
                "{}", 0.93, Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, NOW, NOW)));
        when(documents.observationsOf(DOC)).thenReturn(Flux.fromArray(values));
    }

    private static final Map<String, Object> CRITICAL = UrgencyServiceTest.value("HbA1c", "11.2", "4.0", "5.6");
    private static final Map<String, Object> NORMAL = UrgencyServiceTest.value("HbA1c", "5.0", "4.0", "5.6");

    private void failed() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, null,
                Document.Status.FAILED, "llm_error", null, null, 1, NOW, NOW)));
    }

    private String link() {
        return properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC;
    }

    /** Stock-take wa-failure-reply (1): the run that FAILED on Anthropic credits sent nothing after the ack (prod, 2026-10-16). */
    @Test
    void aDocumentThatEndsFailedTellsTheFamily() {
        failed();
        service.explain(DOC).block();
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        assertThat(sent.getValue().kind().name()).isEqualTo("FAILURE");
        assertThat(sent.getValue().body()).isEqualTo(new Copy().bn("failure.document", Map.of()));
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }

    /**
     * detail() filters DONE before it reaches composeChecked, so this stays green whatever composeChecked does with a
     * FAILED document; it guards detail()'s own filter. The FAILED branch itself is tested below (PR 9 review).
     */
    @Test
    void aDetailRequestOnAFailedDocumentSendsNothingNew() {
        failed();
        StepVerifier.create(service.detail(DOC)).verifyComplete();
        verify(delivery, never()).deliver(any());
    }

    /** The branch PR 9 added, run directly: composeChecked(DETAIL) on FAILED composes nothing — not the notice. */
    @Test
    void aDetailCompositionOnAFailedDocumentComposesNothing() {
        failed();
        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.DETAIL)).verifyComplete();
        verify(delivery, never()).deliver(any());
        verify(messages, never()).save(any());
    }

    /**
     * The real way to send the notice twice: explain() running twice for one FAILED document (a retry, the startup
     * sweep). The second finds the FAILURE row the first stored and composes nothing.
     */
    @Test
    void aSecondExplainOnAFailedDocumentSendsNoSecondNotice() {
        failed();
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.FAILURE)).thenAnswer(i -> Mono.justOrEmpty(lastSaved.get())
                .filter(m -> m.kind() == OutboundMessage.Kind.FAILURE));

        StepVerifier.create(service.explain(DOC)).assertNext(m -> assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.FAILURE))
                .verifyComplete();
        StepVerifier.create(service.explain(DOC)).verifyComplete();

        verify(delivery, times(1)).deliver(any());
        verify(messages, times(1)).save(any());
    }

    /** Gate-sensitive (Global Constraints): under gate-mode=all the notice is parked for the reviewer, never delivered. */
    @Test
    void aFailureNoticeUnderGateAllIsParkedForTheReviewerNotDelivered() {
        properties.getOutbound().setGateMode("all");
        failed();

        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> parked = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(reviewer).notifyPending(parked.capture());
        assertThat(parked.getValue().kind()).isEqualTo(OutboundMessage.Kind.FAILURE);
        assertThat(parked.getValue().gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        assertThat(parked.getValue().body()).isEqualTo(new Copy().bn("failure.document", Map.of()));
        verify(delivery, never()).deliver(any());
    }

    /** And under gate-mode=urgency with the default levels the ROUTINE notice is released and delivered once. */
    @Test
    void aFailureNoticeUnderTheUrgencyGateIsReleasedAndDeliveredOnce() {
        properties.getOutbound().setGateMode("urgency");
        failed();

        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery, times(1)).deliver(sent.capture());
        assertThat(sent.getValue().kind()).isEqualTo(OutboundMessage.Kind.FAILURE);
        verify(reviewer, never()).notifyPending(any());
    }

    /**
     * Inverted from aBodyWithAForeignNumberIsRegeneratedOnceThenFailsClosed, which asserted delivery never() (stock-take
     * wa-failure-reply (3)). Fail-closed still means the MODEL's text never goes: the attempt is stored with no body,
     * as before. What changes is that the family then gets the code-only fallback. Assertions strip the link, because
     * it carries a random UUID (CLAUDE.md: never substring-match over random ids).
     */
    @Test
    void aBodyThatFailsClosedIsStoredWithoutTextAndTheFamilyGetsTheCodeOnlyFallback() {
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2 mmol। এখনই ডাক্তারের কাছে যান। বাকি সব 77।");
        replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। আরও 42 দিন।");

        service.explain(DOC).block();

        verify(metered, times(2)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        ArgumentCaptor<OutboundMessage> saved = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(messages, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(m -> {
            assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
            assertThat(m.body()).isNull();
        });
        String body = sent.getValue().body();
        assertThat(body).contains(link());
        assertThat(body.replace(link(), "")).as("none of the rejected model text")
                .doesNotContain("77").doesNotContain("42").doesNotContain("mmol").doesNotContain("11.2");
        assertThat(body).contains(new Copy().bn("urgency.now", Map.of())).contains(new Copy().bn("safety.red_flag_line", Map.of()));
        assertThat(sent.getValue().urgency()).isEqualTo(Urgency.NOW);
        verify(reviewer, never()).notifyPending(any());
    }

    /** The gate-on direction: under gate=urgency a NOW fallback is parked with the reviewer, never delivered. */
    @Test
    void aFailedClosedNowUnderTheUrgencyGateParksTheFallbackNotDeliversIt() {
        properties.getOutbound().setGateMode("urgency");
        done(CRITICAL);
        replies.push("HbA1c এসেছে 11.2 mmol। এখনই ডাক্তারের কাছে যান। বাকি সব 77।");
        replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। আরও 42 দিন।");

        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> parked = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(reviewer).notifyPending(parked.capture());
        OutboundMessage fallback = parked.getValue();
        assertThat(fallback.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        assertThat(fallback.kind()).isEqualTo(OutboundMessage.Kind.EXPLANATION);
        assertThat(fallback.urgency()).isEqualTo(Urgency.NOW);
        assertThat(fallback.body()).contains(link()).contains(new Copy().bn("urgency.now", Map.of()));
        assertThat(fallback.body().replace(link(), "")).doesNotContain("77").doesNotContain("42").doesNotContain("mmol");
        verify(delivery, never()).deliver(any());
    }

    /** Finding 3: an explanation that throws (here an elided dose, DR-16) still reaches the family as the fallback, without the medicine. */
    @Test
    void anExplanationThatThrowsStillReachesTheFamilyAsTheFallbackWithoutTheMedicine() {
        done(NORMAL);
        when(documents.medicinesOf(DOC)).thenReturn(Flux.just(Map.<String, Object>of(
                "name", "Cap. DDR", "frequency_text", "১+০+০ ... ১+০+১")));
        DocumentFacts facts = new DocumentFacts(documents.find(DOC).block(), "Ma", List.of(NORMAL), List.of(), Map.of(), link());

        StepVerifier.create(service.explain(DOC))
                .assertNext(m -> {
                    assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.EXPLANATION);
                    assertThat(m.urgency()).isEqualTo(Urgency.ROUTINE);
                })
                .verifyComplete();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        assertThat(sent.getValue().kind()).isEqualTo(OutboundMessage.Kind.EXPLANATION);
        assertThat(sent.getValue().body()).as("the fallback, exactly: built from the bundle")
                .isEqualTo(service.fallbackBody(facts, Urgency.ROUTINE));
        assertThat(sent.getValue().body()).doesNotContain("DDR").doesNotContain("১+০+০");
    }

    /** The reason code an operator reads at ERROR: a DR-16 defect is not a model outage (PR 9 review). */
    @Test
    void anElidedMedicineFailsCompositionWithItsOwnReason() {
        done(NORMAL);
        when(documents.medicinesOf(DOC)).thenReturn(Flux.just(Map.<String, Object>of(
                "name", "Cap. DDR", "frequency_text", "১+০+০ ... ১+০+১")));

        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ExplanationCompositionException.class)
                        .extracting(x -> ((ExplanationCompositionException) x).reason())
                        .isEqualTo(ExplanationCompositionException.MEDICINE_ELIDED))
                .verify();
    }

    /**
     * The throw closest to 2026-10-16: the EXPLAIN model call itself errors (credits) on a NOW document. The fallback
     * goes at the stored urgency, so it carries the NOW line and the red-flag line, and none of the values.
     */
    @Test
    void anExplainModelOutageStillSendsTheFallbackAtTheStoredUrgency() {
        done(CRITICAL);
        when(metered.invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("credits")));

        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery, times(1)).deliver(sent.capture());
        assertThat(sent.getValue().kind()).isEqualTo(OutboundMessage.Kind.EXPLANATION);
        assertThat(sent.getValue().urgency()).isEqualTo(Urgency.NOW);
        String body = sent.getValue().body();
        assertThat(body).contains(new Copy().bn("urgency.now", Map.of())).contains(new Copy().bn("safety.red_flag_line", Map.of()));
        assertThat(body.replace(link(), "")).doesNotContain("11.2");
    }

    /**
     * No fallback exists for a NEEDS_RETAKE document: when its retake lookup throws, explain() errors with the original
     * error (DocumentProcessingChain logs it) and nothing is delivered — never a log line claiming a fallback went.
     */
    @Test
    void aRetakeWhoseLookupThrowsPropagatesTheErrorAndSendsNothing() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, NOW, NOW)));
        RuntimeException dbDown = new RuntimeException("connection refused");
        when(messages.consecutiveRetakes(FAMILY)).thenReturn(Mono.error(dbDown));

        StepVerifier.create(service.explain(DOC)).expectErrorMatches(e -> e == dbDown).verify();
        verify(delivery, never()).deliver(any());
        verify(messages, never()).save(any());
    }

    /** The fallback is code; derived over every level, it passes the same checklist as any template and fits the cap. */
    @Test
    void theFallbackPassesTheChecklistAtEveryUrgency() {
        done(NORMAL);
        Document d = documents.find(DOC).block();
        DocumentFacts facts = new DocumentFacts(d, "Ma", List.of(NORMAL), List.of(), Map.of(), link());
        for (Urgency level : Urgency.values()) {
            String body = service.fallbackBody(facts, level);
            assertThat(new MessageSafetyCheck(properties).violations(body, facts, level)).as(level.name()).isEmpty();
            assertThat(body.length()).as(level.name()).isLessThanOrEqualTo(properties.getOutbound().getMaxChars());
            assertThat(body.contains(new Copy().bn("urgency.now", Map.of()))).as(level.name()).isEqualTo(level == Urgency.NOW);
            assertThat(body.contains(new Copy().bn("urgency.this_week", Map.of()))).as(level.name()).isEqualTo(level == Urgency.THIS_WEEK);
        }
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

    /** D35 on the sent body: a one-sided range is not "inside", so the ROUTINE message is filed, not reassuring. */
    @Test
    void aRoutineReportWithAOneSidedRangeIsSentAsFiledNotAsAllNormal() {
        Map<String, Object> ldl = new java.util.HashMap<>(Map.of("id", UUID.randomUUID(), "name", "LDL", "value", "250", "ref_high", "<100"));
        done(ldl);
        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        Copy copy = new Copy();
        String reassurance = copy.bn("explanation.routine", Map.of()).split("\n")[1];       // সব কিছু স্বাভাবিক সীমার মধ্যে আছে।
        String filedLine = copy.bn("explanation.routine.filed", Map.of()).split("\n")[2];   // কাগজে যা লেখা আছে নিচে তুলে দিলাম।
        assertThat(sent.getValue().urgency()).as("one-sided ranges cannot raise urgency yet (D34)").isEqualTo(Urgency.ROUTINE);
        assertThat(sent.getValue().body())
                .as("LDL 250 against '<100' must not be told everything is normal (D35)")
                .doesNotContain(reassurance)
                .contains(filedLine);
    }

    /**
     * D35 on the sent body, with a value that MEETS its one-sided range (the t2 fixture's LDL). 250 is above 100 under
     * any reading, so the test above cannot fail for "one-sided counted as inside"; this one can.
     */
    @Test
    void aRoutineReportWithLdl90AgainstALessThan100RangeIsSentAsFiledNotAsAllNormal() {
        done(new java.util.HashMap<>(Map.of("id", UUID.randomUUID(), "name", "LDL", "value", "90", "ref_high", "<100")));
        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        Copy copy = new Copy();
        String reassurance = copy.bn("explanation.routine", Map.of()).split("\n")[1];       // সব কিছু স্বাভাবিক সীমার মধ্যে আছে।
        String filedLine = copy.bn("explanation.routine.filed", Map.of()).split("\n")[2];   // কাগজে যা লেখা আছে নিচে তুলে দিলাম।
        assertThat(sent.getValue().urgency()).as("90 meets '<100'; nothing raises urgency").isEqualTo(Urgency.ROUTINE);
        assertThat(sent.getValue().body())
                .as("LDL 90 against '<100' is one-sided, not inside: must not be told everything is normal (D35)")
                .doesNotContain(reassurance)
                .contains(filedLine);
    }

    /** The protected direction, on the sent body: a report whose every value is inside by code still reassures. */
    @Test
    void aRoutineReportWhoseEveryValueIsInsideIsSentWithTheReassuranceLine() {
        done(new java.util.HashMap<>(Map.of("id", UUID.randomUUID(), "name", "HbA1c", "value", "5.2", "ref_low", "4.0", "ref_high", "5.6")));
        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        String reassurance = new Copy().bn("explanation.routine", Map.of()).split("\n")[1];   // সব কিছু স্বাভাবিক সীমার মধ্যে আছে।
        assertThat(sent.getValue().body()).as("a truly all-inside report must keep its reassurance").contains(reassurance);
    }

    // ---- F4: compose, release and deliver are three steps; DETAIL is cached per document ----

    private static final String NOW_REPLY = "HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।";

    private static OutboundMessage storedDetail(Urgency urgency, List<String> reasons, OutboundMessage.GateStatus status, String body) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, PATIENT, DOC, OutboundMessage.Kind.DETAIL, urgency, reasons,
                body, status, null, null, null, null, NOW);
    }

    /** F4: composition is pure — no row, no reviewer, no delivery — in both gate modes; the gate status is decided. */
    @ParameterizedTest
    @ValueSource(strings = {"off", "urgency"})
    void composeCheckedNeitherSavesNorParksNorDelivers(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);

        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.DETAIL)).assertNext(m -> {
            assertThat(m.id()).isNull();
            assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.DETAIL);
            assertThat(m.urgency()).isEqualTo(Urgency.NOW);
            assertThat(m.gateStatus()).isEqualTo(mode.equals("off") ? OutboundMessage.GateStatus.RELEASED : OutboundMessage.GateStatus.PENDING);
            assertThat(m.body()).contains(new Copy().bn("urgency.now", Map.of()));
        }).verifyComplete();
        verify(messages, never()).save(any());
        verify(reviewer, never()).notifyPending(any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void composeCheckedForDetailOnARetakeDocumentIsEmpty() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, NOW, NOW)));
        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.DETAIL)).verifyComplete();
        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
                .assertNext(m -> assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.RETAKE)).verifyComplete();
        verify(messages, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "urgency"})
    void releaseParksAPendingMessageAndDeliversOnlyAReleasedOne(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);
        OutboundMessage composed = service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION).block();

        OutboundMessage released = service.release(composed).block();

        verify(messages, times(1)).save(any());
        if (mode.equals("off")) {
            // the claimed path, not any delivery at all: RELEASED, claimed first, then handed to the channel once
            assertThat(released.gateStatus()).isEqualTo(OutboundMessage.GateStatus.RELEASED);
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(messages, delivery);
            order.verify(messages).claimDelivery(released.id());
            order.verify(delivery, times(1)).deliver(any());
            verify(reviewer, never()).notifyPending(any());
        } else {
            assertThat(released.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
            verify(delivery, never()).deliver(any());
            verify(reviewer, times(1)).notifyPending(any());
        }
    }

    @Test
    void releaseRefusesAMessageThatIsAlreadyStored() {
        StepVerifier.create(service.release(storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "x")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("unsaved"))
                .verify();
        verify(messages, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"PENDING", "REJECTED", "FAILED_SAFETY"})
    void deliverRefusesAnythingNotReleasedOrApproved(OutboundMessage.GateStatus status) {
        StepVerifier.create(service.deliver(storedDetail(Urgency.NOW, List.of(), status, "unreviewed")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("gate=" + status.dbValue()))
                .verify();
        verify(delivery, never()).deliver(any());
    }

    /** The cache, protected direction: same urgency and reasons → the stored row, no model call, no new row. */
    @Test
    void detailReturnsTheCachedRowWhenTheUrgencyIsUnchanged() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        verify(messages, never()).save(any());
        verify(delivery, never()).deliver(any());
    }

    /** The cache, dangerous direction: urgency rose since the row was stored → recompose; a stale row would understate it. */
    @Test
    void detailIsRecomposedWhenTheUrgencyHasRisenSinceItWasCached() {
        done(CRITICAL);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(
                storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত")));
        replies.push(NOW_REPLY);

        StepVerifier.create(service.detail(DOC)).assertNext(m -> {
            assertThat(m.urgency()).isEqualTo(Urgency.NOW);
            assertThat(m.body()).isNotEqualTo("আগের বিস্তারিত");
        }).verifyComplete();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        verify(delivery, never()).deliver(any());
    }

    /** HC2 in the cache: a cached row at a HIGHER urgency than a fresh assessment is kept, never replaced by a lower one. */
    @Test
    void aCachedDetailAtAHigherUrgencyIsNeverReplacedByALowerOne() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.NOW, List.of("value_critical:hba1c|stopgap"), OutboundMessage.GateStatus.RELEASED, "জরুরি");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(messages, never()).save(any());
    }

    /** Fail-closed is final for that urgency: regenerating until the checklist passes would make it a filter over chance. */
    @Test
    void aCachedDetailThatFailedSafetyIsNotRegeneratedOnEveryAsk() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.FAILED_SAFETY, null);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }

    /** A miss stores the composed DETAIL (parked if PENDING) and never delivers it — delivery is the caller's call. */
    @ParameterizedTest
    @ValueSource(strings = {"off", "urgency"})
    void aDetailCacheMissIsStoredButNotDelivered(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);

        OutboundMessage m = service.detail(DOC).block();

        assertThat(m.id()).isNotNull();
        assertThat(m.sentAt()).isNull();
        verify(messages, times(1)).save(any());
        verify(delivery, never()).deliver(any());
        verify(reviewer, times(mode.equals("urgency") ? 1 : 0)).notifyPending(any());
    }

    /** Same level, different reasons: the new reason must reach the family, so the cache misses and recomposes. */
    @Test
    void detailIsRecomposedWhenTheLevelIsTheSameButTheReasonsChanged() {
        done(CRITICAL);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(storedDetail(Urgency.NOW,
                List.of("value_critical:another_marker|stopgap"), OutboundMessage.GateStatus.RELEASED, "পুরনো বিস্তারিত")));
        replies.push(NOW_REPLY);

        StepVerifier.create(service.detail(DOC)).assertNext(m -> assertThat(m.body()).isNotEqualTo("পুরনো বিস্তারিত"))
                .verifyComplete();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
    }

    /** A REJECTED detail is a hit: recomposing after a reject would route around the reviewer. */
    @Test
    void aCachedRejectedDetailIsReturnedNotRecomposed() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.REJECTED, "x");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        verify(messages, never()).save(any());
    }

    /**
     * The cache, dangerous direction for the DOCUMENT (DR-12 class): a re-crop rewrites a DONE document's items and
     * stamps updated_at while urgency and reasons can stay the same. A DETAIL composed before that is about values the
     * record no longer holds, and serving it would send them; it is recomposed.
     */
    @Test
    void aCachedDetailComposedBeforeTheDocumentLastChangedIsRecomposed() {
        doneUpdatedAt(NOW, NORMAL);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(storedDetailAt(
                NOW.minusSeconds(3600), Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত")));
        replies.push("HbA1c 5.0, স্বাভাবিক 4.0–5.6।");

        StepVerifier.create(service.detail(DOC))
                .assertNext(m -> assertThat(m.body()).as("a DETAIL older than the document's last change was served")
                        .isNotEqualTo("আগের বিস্তারিত"))
                .verifyComplete();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        verify(messages, times(1)).save(any());
    }

    /** The protected direction: a row composed at or after the document's last change is a hit (no model call). */
    @ParameterizedTest
    @ValueSource(longs = {0, 3600})
    void aCachedDetailComposedAtOrAfterTheDocumentLastChangedIsAHit(long secondsAfterUpdate) {
        doneUpdatedAt(NOW.minusSeconds(7200), NORMAL);
        OutboundMessage cached = storedDetailAt(NOW.minusSeconds(7200 - secondsAfterUpdate), Urgency.ROUTINE, List.of(),
                OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        verify(messages, never()).save(any());
    }

    private void doneUpdatedAt(Instant updatedAt, Map<String, Object>... values) {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-09-14"), "Popular",
                "{}", 0.93, Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, NOW.minusSeconds(86_400), updatedAt)));
        when(documents.observationsOf(DOC)).thenReturn(Flux.fromArray(values));
    }

    private static OutboundMessage storedDetailAt(Instant createdAt, Urgency urgency, List<String> reasons,
                                                  OutboundMessage.GateStatus status, String body) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, PATIENT, DOC, OutboundMessage.Kind.DETAIL, urgency, reasons,
                body, status, null, null, null, null, createdAt);
    }

    /** deliver hands a row to the channel only after winning the claim; a lost claim is empty, not a second send. */
    @Test
    void deliverOnALostClaimSendsNothing() {
        OutboundMessage stored = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "বিস্তারিত");
        // the row exists, so only the lost claim can stop the send (not an absent row)
        when(messages.find(stored.id())).thenReturn(Mono.just(stored));
        org.mockito.Mockito.doReturn(Mono.empty()).when(messages).claimDelivery(stored.id());

        StepVerifier.create(service.deliver(stored)).verifyComplete();
        verify(delivery, never()).deliver(any());
    }

    /** The protected direction of the claim: a won claim hands exactly the claimed row to the channel, once. */
    @Test
    void deliverOnAWonClaimHandsTheRowToTheChannelOnce() {
        OutboundMessage stored = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "বিস্তারিত");
        org.mockito.Mockito.doReturn(Mono.just(stored)).when(messages).claimDelivery(stored.id());
        when(messages.find(stored.id())).thenReturn(Mono.just(stored));

        StepVerifier.create(service.deliver(stored)).expectNextCount(1).verifyComplete();
        verify(delivery, times(1)).deliver(stored);
        verify(messages).markSent(eq(stored.id()), eq(NOW));
    }
    /** F1: a later, lower reading never lowers what is composed: the message is composed at the STORED level. */
    @Test
    void anExplanationIsComposedAtTheStoredLevelWhenAFreshAssessmentIsLower() {
        done(NORMAL);                                                   // assess() alone says ROUTINE
        stored.preset(DOC, Urgency.NOW, List.of("value_critical:hba1c|stopgap"));
        replies.push("HbA1c 5.0। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
                .assertNext(m -> {
                    assertThat(m.urgency()).isEqualTo(Urgency.NOW);
                    assertThat(m.urgencyReasons()).containsExactly("value_critical:hba1c|stopgap");
                })
                .verifyComplete();
    }

    /** The other direction: a higher fresh reading raises what is stored, and the message carries it. */
    @Test
    void aHigherFreshAssessmentRaisesTheStoredLevelAndTheMessage() {
        done(CRITICAL);
        stored.preset(DOC, Urgency.ROUTINE, List.of());
        replies.push("HbA1c 11.2। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
                .assertNext(m -> assertThat(m.urgency()).isEqualTo(Urgency.NOW))
                .verifyComplete();
        assertThat(stored.find(DOC).block().level()).isEqualTo(Urgency.NOW);
    }

    /** DETAIL takes the same path: a cached-or-composed detail states the stored level, never a fresh lower one. */
    @Test
    void aDetailIsComposedAtTheStoredLevelWhenAFreshAssessmentIsLower() {
        done(NORMAL);
        stored.preset(DOC, Urgency.NOW, List.of("value_critical:hba1c|stopgap"));
        replies.push("HbA1c 5.0। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

        StepVerifier.create(service.detail(DOC))
                .assertNext(m -> {
                    assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.DETAIL);
                    assertThat(m.urgency()).isEqualTo(Urgency.NOW);
                    assertThat(m.urgencyReasons()).containsExactly("value_critical:hba1c|stopgap");
                })
                .verifyComplete();
    }

    // ---- F2: the standout line a value earns is its verdict, from the branch that set the urgency ----

    /** The line for one value, as the template renders it: computed through Copy, never hand-typed. */
    private static String lineFor(String key, Map<String, Object> v) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("value_name", String.valueOf(v.get("name")));
        m.put("value", String.valueOf(v.get("value")));
        m.put("unit", String.valueOf(v.get("unit")));
        m.put("ref_low", String.valueOf(v.get("ref_low")));
        m.put("ref_high", String.valueOf(v.get("ref_high")));
        return new Copy().bn(key, m);
    }

    /** What the model was handed: the skeleton is the only place a value's line can come from. */
    private String promptSent() {
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(metered, org.mockito.Mockito.atLeastOnce()).invoke(eq(AiCallPurpose.EXPLAIN), any(), req.capture());
        return req.getAllValues().get(0).userPrompt();
    }

    /**
     * A live threshold raises potassium 6.4 to NOW (nowHigh 6.0) where the stopgap alone (2x ref_high) would only call
     * it outside the range. The message's line for that value must be the critical one, from the same verdict that set
     * NOW, and never "স্বাভাবিক সীমা 3.5–5.1": that is the row reading "outside" under a NOW banner F2 exists to remove.
     */
    @Test
    void aValueALiveThresholdRaisesToNowGetsTheCriticalLineNotTheOutsideOne() {
        properties.getOutbound().getMarkerThresholds().add(new com.elioo.baymax.outbound.domain.MarkerThreshold("potassium",
                "mmol/L", 2.5, 6.0, 3.5, 5.1, false, com.elioo.baymax.outbound.domain.MarkerThreshold.Direction.NORMAL,
                com.elioo.baymax.outbound.domain.MarkerThreshold.Status.ACTIVE, false, "test-signed"));
        Map<String, Object> potassium = ValueVerdictTest.obs("potassium", "6.4", "3.5", "5.1", "mmol/L");
        done(potassium);
        replies.push("potassium এসেছে 6.4। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

        OutboundMessage m = service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION).block();

        assertThat(m.urgency()).isEqualTo(Urgency.NOW);
        assertThat(m.urgencyReasons()).containsExactly("value_critical:potassium|threshold:test-signed");
        assertThat(promptSent())
                .contains(lineFor("line.value_critical_high", potassium))
                .doesNotContain(lineFor("line.value_outside_range", potassium));
    }

    /**
     * The other direction: the stored verdict wins over a fresh read. Stored CRITICAL for potassium (an earlier
     * assessment), and the value now reads inside its printed range: the message is composed at the stored NOW and
     * the line is still the critical one, because a stored verdict never softens and kindOf reads it, not the number.
     */
    @Test
    void aStoredCriticalVerdictKeepsTheCriticalLineWhenAFreshReadWouldNot() {
        Map<String, Object> potassium = ValueVerdictTest.obs("potassium", "4.0", "3.5", "5.1", "mmol/L");   // fresh: inside
        done(potassium);
        UUID obs = (UUID) potassium.get("id");
        stored.rows.put(DOC, new com.elioo.baymax.outbound.domain.StoredUrgency(DOC, Urgency.NOW,
                List.of("value_critical:potassium|stopgap"), Instant.EPOCH, List.of(new com.elioo.baymax.outbound.domain.ValueVerdict(
                        obs, com.elioo.baymax.outbound.domain.ValueVerdict.Kind.CRITICAL, Urgency.NOW,
                        com.elioo.baymax.outbound.domain.ValueVerdict.Side.HIGH, "value_critical:potassium|stopgap"))));
        replies.push("potassium এসেছে 4.0। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

        OutboundMessage m = service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION).block();

        assertThat(m.urgency()).isEqualTo(Urgency.NOW);
        assertThat(promptSent()).contains(lineFor("line.value_critical_high", potassium));
    }
}
