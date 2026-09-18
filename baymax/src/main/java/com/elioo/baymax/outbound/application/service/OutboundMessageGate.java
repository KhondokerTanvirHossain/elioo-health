package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Whether a message waits for the reviewer (DR-13). {@code off}: never; {@code all}: always; {@code urgency}:
 * when its urgency is in the configured list. Decides status only — it never touches urgency or body.
 */
@Component
@RequiredArgsConstructor
public class OutboundMessageGate {

    private final BaymaxProperties properties;

    public OutboundMessage.GateStatus decide(Urgency urgency) {
        String mode = properties.getOutbound().getGateMode() == null ? "off" : properties.getOutbound().getGateMode().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "all" -> OutboundMessage.GateStatus.PENDING;
            case "urgency" -> properties.getOutbound().getGateUrgencyLevels().stream()
                    .map(l -> l.toLowerCase(Locale.ROOT).trim()).anyMatch(l -> l.equals(urgency.dbValue()))
                    ? OutboundMessage.GateStatus.PENDING : OutboundMessage.GateStatus.RELEASED;
            default -> OutboundMessage.GateStatus.RELEASED;
        };
    }
}
