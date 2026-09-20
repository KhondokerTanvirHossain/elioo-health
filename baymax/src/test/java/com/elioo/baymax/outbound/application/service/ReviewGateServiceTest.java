package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewGateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private final OutboundMessagePort messages = mock(OutboundMessagePort.class);
    private final MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
    private final ReviewGateService service = new ReviewGateService(messages, delivery, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID id = UUID.randomUUID();

    private OutboundMessage pending() {
        return new OutboundMessage(id, UUID.randomUUID(), null, UUID.randomUUID(), OutboundMessage.Kind.EXPLANATION, Urgency.NOW,
                List.of("value_critical:hba1c"), "body", OutboundMessage.GateStatus.PENDING, null, null, null, null, NOW);
    }

    /**
     * V13: a provider refusal does not undo the approval and does not claim delivery. The approve call
     * succeeds — the approval genuinely happened — while the row records why nothing arrived.
     */
    @Test
    void aRefusedSendStillApprovesButRecordsTheFailureInsteadOfMarkingItSent() {
        OutboundMessage approved = new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION,
                Urgency.NOW, List.of(), "body", OutboundMessage.GateStatus.APPROVED, "tanvir", null, NOW, null, NOW);
        when(messages.find(id)).thenReturn(Mono.just(pending()));
        when(messages.decide(eq(id), eq(OutboundMessage.GateStatus.APPROVED), eq("tanvir"), isNull(), eq(NOW), isNull()))
                .thenReturn(Mono.just(approved));
        when(delivery.deliver(any())).thenReturn(Mono.just(
                com.elioo.baymax.outbound.domain.DeliveryOutcome.failed(
                        com.elioo.baymax.outbound.domain.DeliveryOutcome.WINDOW_EXPIRED)));
        when(messages.recordDelivery(eq(id), any(), eq(NOW))).thenReturn(Mono.just(approved));

        StepVerifier.create(service.approve(id, "tanvir")).expectNextCount(1).verifyComplete();

        verify(messages, never()).markSent(any(), any());
        org.mockito.ArgumentCaptor<com.elioo.baymax.outbound.domain.DeliveryOutcome> outcome =
                org.mockito.ArgumentCaptor.forClass(com.elioo.baymax.outbound.domain.DeliveryOutcome.class);
        verify(messages).recordDelivery(eq(id), outcome.capture(), eq(NOW));
        assertThat(outcome.getValue().wasSent()).isFalse();
        assertThat(outcome.getValue().error()).isEqualTo("window_expired");
    }

    /** A delivery adapter that throws must not fail the approval either. */
    @Test
    void aDeliveryThatThrowsIsRecordedAsFailedRatherThanBreakingApprove() {
        OutboundMessage approved = new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION,
                Urgency.NOW, List.of(), "body", OutboundMessage.GateStatus.APPROVED, "tanvir", null, NOW, null, NOW);
        when(messages.find(id)).thenReturn(Mono.just(pending()));
        when(messages.decide(eq(id), eq(OutboundMessage.GateStatus.APPROVED), eq("tanvir"), isNull(), eq(NOW), isNull()))
                .thenReturn(Mono.just(approved));
        when(delivery.deliver(any())).thenReturn(Mono.error(new RuntimeException("connection reset")));
        when(messages.recordDelivery(eq(id), any(), eq(NOW))).thenReturn(Mono.just(approved));

        StepVerifier.create(service.approve(id, "tanvir")).expectNextCount(1).verifyComplete();
        verify(messages, never()).markSent(any(), any());
        verify(messages).recordDelivery(eq(id), any(), eq(NOW));
    }

    /** Acceptance: approve delivers; reject leaves it unsent with a reason. */
    @Test
    void approveDeliversAndRejectDoesNot() {
        OutboundMessage approved = new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION,
                Urgency.NOW, List.of(), "body", OutboundMessage.GateStatus.APPROVED, "tanvir", null, NOW, NOW, NOW);
        // find() is called to check PENDING, and again by the log-adapter branch to re-read the saved row;
        // both halves of this test start from a PENDING row, so returning it every time is the honest stub
        when(messages.find(id)).thenReturn(Mono.just(pending()));
        // V13: the decision is persisted with sentAt NULL — only a provider acceptance sets it
        when(messages.decide(eq(id), eq(OutboundMessage.GateStatus.APPROVED), eq("tanvir"), isNull(), eq(NOW), isNull()))
                .thenReturn(Mono.just(approved));
        // the log adapter's outcome: nothing was delivered anywhere, so approve keeps v1 behaviour
        when(delivery.deliver(any())).thenReturn(Mono.just(com.elioo.baymax.outbound.domain.DeliveryOutcome.logged()));
        when(messages.markSent(any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.approve(id, "tanvir")).expectNextCount(1).verifyComplete();
        // V13: the decision is persisted with sentAt NULL, and the log adapter is what stamps it afterwards
        verify(messages).decide(eq(id), eq(OutboundMessage.GateStatus.APPROVED), eq("tanvir"), isNull(), eq(NOW), isNull());
        verify(messages).markSent(eq(id), eq(NOW));
        verify(messages, never()).recordDelivery(any(), any(), any());
        verify(delivery).deliver(any());

        when(messages.decide(eq(id), eq(OutboundMessage.GateStatus.REJECTED), eq("tanvir"), eq("wrong number"), eq(NOW), isNull()))
                .thenReturn(Mono.just(new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION, Urgency.NOW, List.of(), "body",
                        OutboundMessage.GateStatus.REJECTED, "tanvir", "wrong number", NOW, null, NOW)));
        StepVerifier.create(service.reject(id, "tanvir", "wrong number"))
                .assertNext(m -> { assertThat(m.sentAt()).isNull(); assertThat(m.rejectReason()).isEqualTo("wrong number"); }).verifyComplete();
        verify(delivery, org.mockito.Mockito.times(1)).deliver(any());   // still only the approve
    }

    @Test
    void rejectNeedsAReasonAndOnlyPendingCanBeDecided() {
        StepVerifier.create(service.reject(id, "tanvir", " ")).expectError(BaymaxException.class).verify();
        when(messages.find(id)).thenReturn(Mono.just(new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION, Urgency.NOW,
                List.of(), "body", OutboundMessage.GateStatus.RELEASED, null, null, null, NOW, NOW)));
        StepVerifier.create(service.approve(id, "tanvir")).expectErrorMatches(e -> ((BaymaxException) e).status().value() == 409).verify();
        verify(delivery, never()).deliver(any());
    }
}
