package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
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
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A nudge on the BMX-6 path: one NUDGE model call, the checklist with the candidate's numbers, the gate, the opt-out line. */
class NudgeComposerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T06:00:00Z");
    private static final UUID F = UUID.randomUUID(), P = UUID.randomUUID();
    private final OutboundMessagePort messages = mock(OutboundMessagePort.class);
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final ReviewerNotificationPort reviewer = mock(ReviewerNotificationPort.class);
    private final MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final Deque<String> replies = new ArrayDeque<>();
    private NudgeComposer composer;

    private static final String LINK = "https://medioo.eliooo.org/app/nudges/opt-out?p=x&t=abc";
    private static final NudgeCandidate TREND = new NudgeCandidate(NudgeRule.TREND, F, P, "মা", "trend:creatinine:1", NudgeUrgency.THIS_WEEK,
            Map.of("marker", "S. Creatinine", "values", "1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL", "count", "৩", "first_date", "১ মার্চ", "last_date", "১ সেপ্টেম্বর", "direction", "একটু একটু করে বাড়ছে"),
            Set.of("1", "3", "5"), List.of("c1", "c2", "c3"), null);

    @BeforeEach
    void wire() {
        LlmClient sonnet = mock(LlmClient.class);
        composer = new NudgeComposer(messages, metered, new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new MessageSafetyCheck(props), new OutboundMessageGate(props), reviewer, delivery, new Copy(), props, Clock.fixed(NOW, ZoneOffset.UTC));
        when(metered.using(any())).thenReturn(metered);
        when(metered.invoke(eq(AiCallPurpose.NUDGE), any(), any(LlmRequest.class))).thenAnswer(i -> Mono.just(
                new LlmResponse(replies.isEmpty() ? "" : replies.pop(), "stop", new TokenUsage(300, 80), "claude-sonnet-5", null, "anthropic", 700L)));
        when(messages.save(any())).thenAnswer(i -> {
            OutboundMessage m = i.getArgument(0);
            return Mono.just(new OutboundMessage(UUID.randomUUID(), m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                    m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), m.sentAt(), m.createdAt()));
        });
        when(reviewer.notifyPending(any())).thenReturn(Mono.empty());
        when(delivery.deliver(any())).thenReturn(Mono.empty());
    }

    /** Acceptance: gate-nudges=true → nothing sends without approve, whatever gate-mode says. */
    @Test
    void everyNudgeIsGatedDuringThePilotAndCarriesTheOptOutLine() {
        props.getOutbound().setGateMode("off");
        replies.push("S. Creatinine গত 3টি রিপোর্টে একই দিকে বদলাচ্ছে: 1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL। এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।");
        OutboundMessage m = composer.compose(TREND, LINK).block();
        assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.NUDGE);
        assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        assertThat(m.urgency()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(m.body()).contains("1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL").endsWith(LINK);
        assertThat(m.body()).contains("এই ধরনের বার্তা আর চান না?");
        verify(reviewer).notifyPending(any());
        verify(delivery, never()).deliver(any());
        // one model call, purpose NUDGE, and the skeleton in the prompt carries the values verbatim
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(metered).invoke(eq(AiCallPurpose.NUDGE), any(), req.capture());
        assertThat(req.getValue().userPrompt()).contains("1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL");
    }

    /** The checklist applies unchanged: a foreign number is regenerated once, then fails closed. */
    @Test
    void aForeignNumberFailsTheChecklistLikeAnyMessage() {
        replies.push("ক্রিয়েটিনিন 1.1 → 1.3 → 1.5 আর 42 দিন। ডাক্তার দেখান।");
        replies.push("ক্রিয়েটিনিন 1.1 → 1.3 → 1.5 আর 42 দিন। ডাক্তার দেখান।");
        OutboundMessage m = composer.compose(TREND, LINK).block();
        assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(m.body()).isNull();
        verify(reviewer, never()).notifyPending(any());
    }

    @Test
    void withTheGateOffARoutineNudgeReleasesAndDelivers() {
        props.getOutbound().setGateNudges(false);
        props.getOutbound().setGateMode("off");
        NudgeCandidate follow = new NudgeCandidate(NudgeRule.FOLLOW_UP_DUE, F, P, "মা", "follow_up:1", NudgeUrgency.ROUTINE,
                Map.of("instruction", "Follow up after 1 month", "due_date", "২১ সেপ্টেম্বর"), Set.of("1", "21"), List.of("c"), java.time.LocalDate.parse("2026-09-21"));
        replies.push("মা-এর ফলো-আপের তারিখ কাছে এসে গেছে: ২১ সেপ্টেম্বর। প্রেসক্রিপশনে লেখা: \"Follow up after 1 month\"।");
        OutboundMessage m = composer.compose(follow, LINK).block();
        assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.RELEASED);
        assertThat(m.sentAt()).isEqualTo(NOW);
        verify(delivery).deliver(any());
    }

    /** PO ruling 2026-09-19: the cap covers what the family receives, opt-out line included. */
    @Test
    void theCapCoversTheWholeBodyIncludingTheOptOutLine() {
        props.getOutbound().setMaxChars(200);   // opt-out line + link ≈ 110 chars → the model gets ≈ 90
        replies.push("S. Creatinine বদলাচ্ছে: 1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL। ডাক্তার দেখান।");
        OutboundMessage ok = composer.compose(TREND, LINK).block();
        assertThat(ok.body()).isNotNull();
        assertThat(ok.body().length()).isLessThanOrEqualTo(200);
        String tooLong = "S. Creatinine বদলাচ্ছে: 1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL। ডাক্তার দেখান। " + "ক".repeat(60);
        replies.push(tooLong);
        replies.push(tooLong);
        OutboundMessage failed = composer.compose(TREND, LINK).block();
        assertThat(failed.gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);   // over budget twice → fails closed
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(metered, org.mockito.Mockito.atLeastOnce()).invoke(eq(AiCallPurpose.NUDGE), any(), req.capture());
        assertThat(req.getValue().systemPrompt()).doesNotContain("Under 200 characters");   // the model is told its budget, not the cap
    }
}
