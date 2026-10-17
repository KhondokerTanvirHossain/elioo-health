package com.elioo.baymax.wa.adapter.out;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4: the gate invariant where every writer meets it. Release, approve and nudges all reach WhatsApp through this
 * adapter, so "nothing unreviewed reaches a family" is asserted here once rather than per caller.
 */
class WhatsAppMessageDeliveryTest {

    private static final UUID FAMILY = UUID.randomUUID();
    private static final String E164 = "+8801700000000";
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private WhatsAppMessageDelivery delivery;

    @BeforeEach
    void wire() {
        props.getWa().setAllowlist(List.of(E164));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(WaFixtures.wamid("8801700000000")));
        delivery = new WhatsAppMessageDelivery(graph, records, props);
    }

    private static OutboundMessage row(OutboundMessage.GateStatus status) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, null, UUID.randomUUID(), OutboundMessage.Kind.DETAIL,
                Urgency.NOW, List.of(), "এখনও কেউ দেখেননি এমন লেখা", status, null, null, null, null, Instant.EPOCH);
    }

    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"PENDING", "REJECTED", "FAILED_SAFETY"})
    void aMessageThatIsNotReleasedOrApprovedNeverReachesWhatsapp(OutboundMessage.GateStatus status) {
        DeliveryOutcome outcome = delivery.deliver(row(status)).block();

        verify(graph, never()).sendText(anyString(), anyString());
        assertThat(outcome.wasSent()).isFalse();
        assertThat(outcome.error()).isEqualTo("not_released");
    }

    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"RELEASED", "APPROVED"})
    void aReleasedOrApprovedMessageIsSent(OutboundMessage.GateStatus status) {
        assertThat(delivery.deliver(row(status)).block().wasSent()).isTrue();
        verify(graph, times(1)).sendText(E164, "এখনও কেউ দেখেননি এমন লেখা");
    }
}
