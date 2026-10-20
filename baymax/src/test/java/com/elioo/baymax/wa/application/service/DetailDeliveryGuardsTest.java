package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.InMemoryOutboundMessages;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.ExplanationService;
import com.elioo.baymax.outbound.application.service.MessageSafetyCheck;
import com.elioo.baymax.outbound.application.service.OutboundMessageGate;
import com.elioo.baymax.outbound.application.service.ReviewGateService;
import com.elioo.baymax.outbound.application.service.StoredUrgencyService;
import com.elioo.baymax.outbound.application.service.UrgencyLines;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 guards, end to end: the real ExplanationService, ReviewGateService and WaIntakeService over ONE mocked WhatsApp
 * port, so every path that can put text on the family's phone is counted — release, approve and onText alike.
 * Written against APIs that exist before the fix, so each was replayed red on the pre-fix source and failed ON the defect.
 */
class DetailDeliveryGuardsTest {

    private static final String FROM = "8801700000000", E164 = "+8801700000000";
    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    private static final UUID DOC = UUID.randomUUID(), PATIENT = UUID.randomUUID(), FAMILY = UUID.randomUUID();
    /** model replies the checklist accepts (same strings ExplanationServiceTest already uses) */
    private static final String ROUTINE_REPLY = "HbA1c 5.0, স্বাভাবিক 4.0–5.6।";
    private static final String NOW_REPLY = "HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।";

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InMemoryOutboundMessages messages = new InMemoryOutboundMessages();
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final LlmClient sonnet = mock(LlmClient.class);
    private final ReviewerNotificationPort reviewer = mock(ReviewerNotificationPort.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final Deque<String> replies = new ArrayDeque<>();
    private final AtomicInteger wamids = new AtomicInteger();
    /** advanced by a test that needs time to pass (a re-crop after the detail); every other test stays at T0 */
    private final MutableClock clock = new MutableClock(T0);
    private ExplanationService explainer;
    private ReviewGateService reviewGate;
    private WaIntakeService wa;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        MessageDeliveryPort whatsapp = new WhatsAppMessageDelivery(graph, records, props);
        StoredUrgencyService storedUrgency = new StoredUrgencyService(new UrgencyService(props, clock),
                new com.elioo.baymax.outbound.application.service.InMemoryDocumentUrgency(), documents, records, props, clock);
        explainer = new ExplanationService(documents, records, messages, metered,
                new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new MessageSafetyCheck(props), new OutboundMessageGate(props),
                reviewer, whatsapp, new Copy(), props, clock, storedUrgency);
        reviewGate = new ReviewGateService(messages, whatsapp, clock);
        com.elioo.baymax.family.application.port.in.FreeTierUseCase freeTier =
                mock(com.elioo.baymax.family.application.port.in.FreeTierUseCase.class);
        when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.empty());
        wa = new WaIntakeService(records, documents, explainer, mock(DocumentIntakeUseCase.class), graph, new Copy(), props,
                mock(com.elioo.baymax.web.application.port.in.WebAuthUseCase.class), clock, freeTier,
                WaFixtures.noUrgentPapers(), storedUrgency);

        when(graph.sendText(anyString(), anyString())).thenAnswer(i -> Mono.just(WaFixtures.wamid(FROM, wamids.incrementAndGet())));
        when(metered.using(any())).thenReturn(metered);
        when(metered.invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class))).thenAnswer(i -> Mono.just(
                new LlmResponse(replies.isEmpty() ? "" : replies.pop(), "stop", new TokenUsage(500, 120), "claude-sonnet-5", null, "anthropic", 900L)));
        when(reviewer.notifyPending(any())).thenReturn(Mono.empty());
        FamilyAccount family = new FamilyAccount(FAMILY, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH);
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.just(family));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(family));
        when(records.findPatient(PATIENT)).thenReturn(Mono.just(
                new PatientProfile(PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), T0, T0)));
        document(T0);
        when(documents.followUpsOf(DOC)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(DOC)).thenReturn(Mono.empty());
        when(documents.medicinesOf(DOC)).thenReturn(Flux.empty());
    }

    /** The DONE document as last written at {@code updatedAt} (extraction or a re-crop). */
    private void document(Instant updatedAt) {
        Document doc = new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-10-14"), "Shapla Diagnostics", "{}", 0.93,
                Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, T0, updatedAt);
        when(documents.find(DOC)).thenReturn(Mono.just(doc));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    }

    private void observations(String name, String value, String low, String high) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", UUID.randomUUID());                   // every persisted observation has one; F2 joins on it
        v.put("name", name);
        v.put("value", value);
        v.put("ref_low", low);
        v.put("ref_high", high);
        when(documents.observationsOf(DOC)).thenReturn(Flux.just(v));
    }

    private List<String> allSent() {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph, atLeast(0)).sendText(eq(E164), sent.capture());
        return sent.getAllValues();
    }

    /** Sends that ARE a stored message body — field equality, never a substring of the document. */
    private List<String> bodiesSent() {
        Set<String> bodies = messages.rows().stream().map(OutboundMessage::body).filter(Objects::nonNull).collect(Collectors.toSet());
        return allSent().stream().filter(bodies::contains).toList();
    }

    private OutboundMessage firstDetail() {
        return messages.rows().stream().filter(m -> m.kind() == OutboundMessage.Kind.DETAIL).findFirst().orElseThrow();
    }

    private String link() {
        return props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC;
    }

    /** gate=off (production): one ask, one body on the phone. */
    @Test
    void gateOffTheDetailArrivesExactlyOnce() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().body()).as("vacuity guard: a body exists that could be sent twice").isNotBlank();
        assertThat(bodiesSent())
                .as("the detail arrived twice: once from release(), once from onText itself (F4)")
                .hasSize(1);
    }

    /** gate=off: a second ask neither re-sends the body nor pays for a second composition. */
    @Test
    void gateOffAskingTwiceSendsTheBodyOnceAndComposesOnce() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);
        replies.push(ROUTINE_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(bodiesSent()).as("each ask re-composed and re-sent the detail (no DETAIL cache, F4)").hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(allSent()).hasSize(2);                     // the body, then the fixed already-sent reply
        assertThat(allSent().get(1)).isEqualTo(new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
    }

    /** gate=urgency, NOW: the PENDING body never reaches WhatsApp by any path; the family gets the urgency at once (D5). */
    @Test
    void underTheUrgencyGateAPendingDetailIsNeverSent() {
        props.getOutbound().setGateMode("urgency");
        observations("HbA1c", "11.2", "4.0", "5.6");
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();

        OutboundMessage detail = firstDetail();
        assertThat(detail.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        assertThat(detail.body()).as("vacuity guard: an unreviewed body exists that could leak").isNotBlank();
        assertThat(bodiesSent())
                .as("a PENDING body reached WhatsApp — onText sent it itself, bypassing the review gate (F4)")
                .isEmpty();
        verify(reviewer, times(1)).notifyPending(any());
        Copy copy = new Copy();
        assertThat(allSent()).singleElement().satisfies(s -> {
            assertThat(s).startsWith(copy.bn("urgency.now", Map.of()))
                    .contains(copy.bn("safety.red_flag_line", Map.of()))
                    .contains(copy.bn("wa.detail.pending", Map.of()));
            assertThat(s.length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        });
    }

    /** gate=urgency: ask, approve, ask again — the approved body reaches the phone once, from approve, and never again. */
    @Test
    void underTheUrgencyGateTheApprovedDetailArrivesExactlyOnce() {
        props.getOutbound().setGateMode("urgency");
        observations("HbA1c", "11.2", "4.0", "5.6");
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        reviewGate.approve(firstDetail().id(), "tanvir").block();

        // approve itself delivered it: asserted here, before the second ask could deliver the unsent row instead
        assertThat(bodiesSent()).as("approve did not deliver the approved detail").hasSize(1);
        assertThat(firstDetail().sentAt()).as("approve's delivery is recorded on the row").isNotNull();

        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().body()).isNotBlank();
        assertThat(bodiesSent())
                .as("the body went out before approval (onText) and again after it (F4)")
                .hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        Copy copy = new Copy();
        List<String> sent = allSent();
        assertThat(sent.get(sent.size() - 1)).isEqualTo(UrgencyLines.lead(copy, Urgency.NOW,
                copy.bn("wa.detail.already_sent", Map.of("link", link()))));
    }

    /** The web endpoint asks for the detail to SHOW it; it must not text the family as a side effect. */
    @Test
    void aWebDetailRequestStoresTheMessageAndSendsNothingOnWhatsapp() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);

        OutboundMessage shown = explainer.detail(DOC).block();

        assertThat(shown.body()).isNotBlank();
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** Both gate modes, a ROUTINE and a NOW document: fail-closed is gate-independent, and an urgent one leads with the urgency (D5). */
    static Stream<Arguments> gateModesAndLevels() {
        return Stream.of(Arguments.of("off", Urgency.ROUTINE), Arguments.of("off", Urgency.NOW),
                Arguments.of("urgency", Urgency.ROUTINE), Arguments.of("urgency", Urgency.NOW));
    }

    private void fixture(Urgency level) {
        if (level == Urgency.NOW) {
            observations("HbA1c", "11.2", "4.0", "5.6");
        } else {
            observations("HbA1c", "5.0", "4.0", "5.6");
        }
    }

    private String unavailable(Urgency level) {
        Copy copy = new Copy();
        return UrgencyLines.lead(copy, level, copy.bn("wa.detail.unavailable", Map.of("link", link())));
    }

    /** A family that typed the word we asked for gets an answer even when the detail failed closed. */
    @ParameterizedTest(name = "gate={0} urgency={1}")
    @MethodSource("gateModesAndLevels")
    void aDetailThatFailedClosedIsAnsweredNotSilent(String mode, Urgency level) {
        props.getOutbound().setGateMode(mode);
        fixture(level);                                           // no replies queued: the model returns "", twice

        wa.onText(FROM, "বিস্তারিত").block();

        verify(graph, atLeastOnce()).sendText(eq(E164), anyString());
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(firstDetail().urgency()).as("vacuity: the fixture is at the level under test").isEqualTo(level);
        assertThat(allSent()).singleElement().satisfies(s -> {
            assertThat(s).isEqualTo(unavailable(level));
            assertThat(s.length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        });
    }

    /** A detail request on a NEEDS_RETAKE document must not file another retake (it escalates the next retake copy). */
    @Test
    void aDetailRequestOnARetakeDocumentFilesNoRetakeRow() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, T0, T0)));

        StepVerifier.create(explainer.detail(DOC)).verifyComplete();

        assertThat(messages.rows()).as("a detail request filed a RETAKE row").isEmpty();
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** After a fail-closed detail, asking again is final: no second model call, no body, the same fixed reply. */
    @ParameterizedTest(name = "gate={0} urgency={1}")
    @MethodSource("gateModesAndLevels")
    void aSecondAskAfterAFailClosedDetailCostsNothingAndSendsNoBody(String mode, Urgency level) {
        props.getOutbound().setGateMode(mode);
        fixture(level);                                           // no replies queued: the model returns "" (fail-closed)

        wa.onText(FROM, "বিস্তারিত").block();
        Mockito.clearInvocations(metered);
        wa.onText(FROM, "বিস্তারিত").block();

        verify(metered, never()).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(messages.rows()).filteredOn(m -> m.kind() == OutboundMessage.Kind.DETAIL).hasSize(1);
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(allSent()).containsExactly(unavailable(level), unavailable(level));
    }

    /** gate=urgency: ask, the reviewer REJECTS, ask again — no body ever, no second model call, the urgency first (D5). */
    @Test
    void underTheUrgencyGateARejectedDetailIsNeverSentAndAskingAgainIsFinal() {
        props.getOutbound().setGateMode("urgency");
        fixture(Urgency.NOW);
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        reviewGate.reject(firstDetail().id(), "tanvir", "wording").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.REJECTED);
        assertThat(firstDetail().body()).as("vacuity guard: a rejected body exists that could leak").isNotBlank();
        assertThat(bodiesSent()).as("a REJECTED body reached WhatsApp (F4)").isEmpty();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(messages.rows()).filteredOn(m -> m.kind() == OutboundMessage.Kind.DETAIL).hasSize(1);
        List<String> sent = allSent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(unavailable(Urgency.NOW));
        assertThat(sent.get(1).length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
    }

    /**
     * gate=off (production) on an URGENT document: the NOW detail is released without review, so it is the path a
     * family actually takes. One body, one model call; the second ask is exactly the urgency block and the pointer.
     */
    @Test
    void gateOffAnUrgentDetailArrivesOnceAndTheSecondAskLeadsWithTheUrgency() {
        fixture(Urgency.NOW);
        replies.push(NOW_REPLY);
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().gateStatus()).as("vacuity: gate off releases NOW").isEqualTo(OutboundMessage.GateStatus.RELEASED);
        assertThat(firstDetail().urgency()).isEqualTo(Urgency.NOW);
        assertThat(bodiesSent()).as("the urgent detail reached the phone more than once (F4)").hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        Copy copy = new Copy();
        String pointer = UrgencyLines.lead(copy, Urgency.NOW, copy.bn("wa.detail.already_sent", Map.of("link", link())));
        List<String> sent = allSent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(pointer);
        assertThat(pointer.length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
    }

    /** The web stores a RELEASED, undelivered DETAIL; the family's later asks deliver THAT row once, with no second model call. */
    @Test
    void aDetailShownOnTheWebIsDeliveredOnceWhenTheFamilyAsksOnWhatsapp() {
        fixture(Urgency.ROUTINE);
        replies.push(ROUTINE_REPLY);
        replies.push(ROUTINE_REPLY);

        OutboundMessage shown = explainer.detail(DOC).block();
        wa.onText(FROM, "বিস্তারিত").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(shown.body()).isNotBlank();
        assertThat(bodiesSent()).as("the web-stored detail reached the phone other than exactly once").containsExactly(shown.body());
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(allSent()).hasSize(2);
        assertThat(allSent().get(1)).isEqualTo(new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
    }

    /**
     * DR-12 class, end to end: the web composes and stores a DETAIL; a re-crop then rewrites the document's values
     * (urgency and reasons unchanged, updated_at stamped); the family types বিস্তারিত. The stored row was never
     * delivered, so serving it from the cache would send a value the record no longer holds.
     */
    @Test
    void aDetailComposedBeforeARecropIsNeverSentAfterIt() {
        fixture(Urgency.ROUTINE);
        replies.push(ROUTINE_REPLY);
        OutboundMessage stale = explainer.detail(DOC).block();

        clock.advance(Duration.ofHours(1));
        document(clock.instant());                                // the re-crop's finish() stamps updated_at
        observations("HbA1c", "5.2", "4.0", "5.6");               // and the value now on the record differs
        replies.push("HbA1c 5.2, স্বাভাবিক 4.0–5.6।");
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(stale.body()).as("vacuity: the stale body carries the old value").contains("5.0");
        assertThat(allSent()).as("a DETAIL composed before the re-crop was sent after it")
                .doesNotContain(stale.body())
                .singleElement().satisfies(s -> assertThat(s).contains("5.2"));
        verify(metered, times(2)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
    }

    /**
     * Two asks that both miss the cache — a double tap within the model-call window — compose once and send the body
     * once. Before, each composed (two paid calls), each stored its own row and each won the claim on its own row.
     */
    @Test
    void twoAsksThatBothMissTheCacheComposeOnceAndSendTheBodyOnce() {
        fixture(Urgency.ROUTINE);
        Sinks.One<LlmResponse> model = Sinks.one();
        when(metered.invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class))).thenReturn(model.asMono());

        Mono<WaIntakeService.Outcome> first = wa.onText(FROM, "বিস্তারিত").cache();
        Mono<WaIntakeService.Outcome> second = wa.onText(FROM, "বিস্তারিত").cache();
        first.subscribe();
        second.subscribe();                                       // both are now waiting on the one model reply
        model.tryEmitValue(new LlmResponse(ROUTINE_REPLY, "stop", new TokenUsage(500, 120), "claude-sonnet-5", null, "anthropic", 900L));
        first.block(Duration.ofSeconds(5));
        second.block(Duration.ofSeconds(5));

        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(messages.rows()).filteredOn(m -> m.kind() == OutboundMessage.Kind.DETAIL).hasSize(1);
        assertThat(bodiesSent()).as("two concurrent asks each composed, stored and delivered a detail").hasSize(1);
        assertThat(allSent()).hasSize(2);
    }

    /**
     * gate=urgency, the WEB asks for a NOW detail: it is stored PENDING and nothing is sent. The reviewer's approval then
     * delivers it on WhatsApp, once (ReviewGateService.approve claims and delivers; that is its job). Stated for the PO:
     * under the urgency gate a web-requested detail reaches the phone on approval, possibly outside the 24-hour window.
     */
    @Test
    void underTheUrgencyGateAWebDetailIsSentOnlyByTheApprovalAndOnce() {
        props.getOutbound().setGateMode("urgency");
        fixture(Urgency.NOW);
        replies.push(NOW_REPLY);

        OutboundMessage shown = explainer.detail(DOC).block();
        assertThat(shown.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        verify(graph, never()).sendText(anyString(), anyString());

        reviewGate.approve(shown.id(), "tanvir").block();
        reviewGate.approve(shown.id(), "tanvir").onErrorResume(e -> Mono.empty()).block();   // a second click

        assertThat(bodiesSent()).containsExactly(shown.body());
    }

    /** A fixed clock that a test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
