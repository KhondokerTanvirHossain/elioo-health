package com.elioo.baymax.outbound.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every reason prefix the urgency rule can raise with ({@code <prefix>[:<marker>][|stopgap|threshold:<src>]}). Pages
 * derive their reason blocks from this enum (B3), and {@link UrgencyAssessment#raise} refuses a prefix that is not here.
 */
public enum UrgencyReason {
    VALUE_CRITICAL("value_critical"),
    VALUE_OUTSIDE_RANGE("value_outside_range"),
    UNVERIFIED_VALUE_CRITICAL("unverified_value_critical"),
    UNVERIFIED_VALUE_OUTSIDE_RANGE("unverified_value_outside_range"),
    FOLLOW_UP_PAST_DUE("follow_up_past_due"),
    DIAGNOSIS_PRESENT("diagnosis_present"),
    TEXT_EMERGENCY_PHRASE("text_emergency_phrase");

    private final String prefix;

    UrgencyReason(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /** "value_critical:potassium|stopgap" for {@code VALUE_CRITICAL.of("potassium", "|stopgap")}. */
    public String of(String marker, String rule) {
        return prefix + (marker == null ? "" : ":" + marker) + (rule == null ? "" : rule);
    }

    public static Optional<UrgencyReason> ofReason(String reason) {
        if (reason == null) {
            return Optional.empty();
        }
        String p = reason.split("[:|]", 2)[0];
        return Arrays.stream(values()).filter(r -> r.prefix.equals(p)).findFirst();
    }
}
