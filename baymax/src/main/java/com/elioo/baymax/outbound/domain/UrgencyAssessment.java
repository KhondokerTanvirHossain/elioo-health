package com.elioo.baymax.outbound.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An urgency and the machine-readable reasons for it ({@code value_critical:hba1c|stopgap}, {@code diagnosis_present},
 * {@code follow_up_past_due}, {@code text_emergency_phrase}). Reasons carry marker names, never document text.
 * Only ever raised: {@link #raise} returns the higher of the two, and refuses a reason whose prefix
 * {@link UrgencyReason} does not name.
 *
 * <p>F2: {@code values} holds one {@link ValueVerdict} per shown value, from the same branch of the rule that raised
 * (or did not raise) the document. A raising row's reason is one of {@link #reasons}, by construction.
 */
public record UrgencyAssessment(Urgency level, List<String> reasons, List<ValueVerdict> values) {

    public UrgencyAssessment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** No verdicts: what a stored level read back without its rows, or a legacy reason list, looks like. */
    public UrgencyAssessment(Urgency level, List<String> reasons) {
        this(level, reasons, List.of());
    }

    public static UrgencyAssessment routine() {
        return new UrgencyAssessment(Urgency.ROUTINE, List.of());
    }

    public UrgencyAssessment raise(Urgency to, String reason) {
        UrgencyReason.ofReason(reason)
                .orElseThrow(() -> new IllegalArgumentException("unknown urgency reason prefix: " + reason));
        List<String> merged = new ArrayList<>(reasons);
        merged.add(reason);
        return new UrgencyAssessment(Urgency.max(level, to), List.copyOf(merged), values);
    }

    public UrgencyAssessment withValues(List<ValueVerdict> verdicts) {
        return new UrgencyAssessment(level, reasons, verdicts);
    }
}
