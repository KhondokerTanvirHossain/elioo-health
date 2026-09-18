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

    /** Acceptance: approve delivers; reject leaves it unsent with a reason. */
    @Test
    void approveDeliversAndRejectDoesNot() {
        when(messages.find(id)).thenReturn(Mono.just(pending()));
        when(messages.decide(eq(id), eq(OutboundMessage.GateStatus.APPROVED), eq("tanvir"), isNull(), eq(NOW), eq(NOW)))
                .thenReturn(Mono.just(new OutboundMessage(id, null, null, null, OutboundMessage.Kind.EXPLANATION, Urgency.NOW, List.of(), "body",
                        OutboundMessage.GateStatus.APPROVED, "tanvir", null, NOW, NOW, NOW)));
        when(delivery.deliver(any())).thenReturn(Mono.empty());
        StepVerifier.create(service.approve(id, "tanvir")).assertNext(m -> assertThat(m.sentAt()).isEqualTo(NOW)).verifyComplete();
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
