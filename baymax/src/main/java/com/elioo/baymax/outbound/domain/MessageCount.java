package com.elioo.baymax.outbound.domain;

/** Weekly-export row: how many messages at an urgency reached a gate outcome. */
public record MessageCount(Urgency urgency, OutboundMessage.GateStatus gateStatus, long count) {
}
