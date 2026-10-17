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
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
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
    private ExplanationService explainer;
    private ReviewGateService reviewGate;
    private WaIntakeService wa;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        MessageDeliveryPort whatsapp = new WhatsAppMessageDelivery(graph, records, props);
        explainer = new ExplanationService(documents, records, messages, metered,
                new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new UrgencyService(props, clock), new MessageSafetyCheck(props), new OutboundMessageGate(props),
                reviewer, whatsapp, new Copy(), props, clock);
        reviewGate = new ReviewGateService(messages, whatsapp, clock);
        wa = new WaIntakeService(records, documents, explainer, mock(DocumentIntakeUseCase.class), graph, new Copy(), props);

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
        Document doc = new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-10-14"), "Shapla Diagnostics", "{}", 0.93,
                Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, T0, T0);
        when(documents.find(DOC)).thenReturn(Mono.just(doc));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
        when(documents.followUpsOf(DOC)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(DOC)).thenReturn(Mono.empty());
        when(documents.medicinesOf(DOC)).thenReturn(Flux.empty());
    }

    private void observations(String name, String value, String low, String high) {
        Map<String, Object> v = new HashMap<>();
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
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().body()).isNotBlank();
        assertThat(bodiesSent())
                .as("the body went out before approval (onText) and again after it (F4)")
                .hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
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

    /** A family that typed the word we asked for gets an answer even when the detail failed closed. */
    @Test
    void aDetailThatFailedClosedIsAnsweredNotSilent() {
        observations("HbA1c", "5.0", "4.0", "5.6");               // no replies queued: the model returns "", twice

        wa.onText(FROM, "বিস্তারিত").block();

        verify(graph, atLeastOnce()).sendText(eq(E164), anyString());
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(allSent()).singleElement().isEqualTo(new Copy().bn("wa.detail.unavailable", Map.of("link", link())));
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
    @Test
    void aSecondAskAfterAFailClosedDetailCostsNothingAndSendsNoBody() {
        observations("HbA1c", "5.0", "4.0", "5.6");               // no replies queued: the model returns "" (fail-closed)

        wa.onText(FROM, "বিস্তারিত").block();
        Mockito.clearInvocations(metered);
        wa.onText(FROM, "বিস্তারিত").block();

        verify(metered, never()).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(messages.rows()).filteredOn(m -> m.kind() == OutboundMessage.Kind.DETAIL).hasSize(1);
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        String unavailable = new Copy().bn("wa.detail.unavailable", Map.of("link", link()));
        assertThat(allSent()).containsExactly(unavailable, unavailable);
    }
}
