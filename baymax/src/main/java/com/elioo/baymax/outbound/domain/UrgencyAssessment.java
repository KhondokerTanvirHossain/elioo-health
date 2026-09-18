package com.elioo.baymax.outbound.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An urgency and the machine-readable reasons for it ({@code value_critical:hba1c}, {@code diagnosis_present},
 * {@code follow_up_past_due}, {@code text_emergency_phrase}). Reasons carry marker names, never document text.
 * Only ever raised: {@link #raise} returns the higher of the two.
 */
public record UrgencyAssessment(Urgency level, List<String> reasons) {

    public static UrgencyAssessment routine() {
        return new UrgencyAssessment(Urgency.ROUTINE, List.of());
    }

    public UrgencyAssessment raise(Urgency to, String reason) {
        List<String> merged = new ArrayList<>(reasons);
        merged.add(reason);
        return new UrgencyAssessment(Urgency.max(level, to), List.copyOf(merged));
    }
}
