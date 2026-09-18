package com.elioo.baymax.nudge.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A persisted nudge row: the dedupe key plus its outcome. */
public record Nudge(UUID id, UUID familyId, UUID patientId, NudgeRule rule, String triggerKey, NudgeUrgency urgency,
                    NudgeStatus status, String dropReason, Map<String, String> vars, UUID messageId, Instant holdUntil,
                    Instant createdAt, Instant resolvedAt) {
}
