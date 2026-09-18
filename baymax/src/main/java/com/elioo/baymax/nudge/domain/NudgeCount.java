package com.elioo.baymax.nudge.domain;

import java.util.UUID;

/** Export row: nudges by rule and outcome per family in a window. */
public record NudgeCount(UUID familyId, NudgeRule rule, NudgeStatus status, String dropReason, long count) {
}
