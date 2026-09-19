package com.elioo.baymax.nudge.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a rule found, before policy: the dedupe key, the urgency, the template variables carrying the extraction's
 * own strings, the digit runs those strings contain (the closed set the safety check allows), and the crops that
 * ground it.
 */
public record NudgeCandidate(NudgeRule rule, UUID familyId, UUID patientId, String patientName, String triggerKey,
                             NudgeUrgency urgency, Map<String, String> vars, Set<String> numbers, List<String> cropKeys,
                             java.time.LocalDate deadline) {

    /** The date after which a deferred nudge is pointless (DR-19); null for rules that are not date-bound. */
    public boolean expired(java.time.LocalDate today) {
        return deadline != null && today.isAfter(deadline);
    }

    public NudgeCandidate withPatientName(String name) {
        return new NudgeCandidate(rule, familyId, patientId, name, triggerKey, urgency, vars, numbers, cropKeys, deadline);
    }
}
