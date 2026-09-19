package com.elioo.baymax.nudge.domain;

import java.util.Locale;

/**
 * The five rules (BMX-8). Declaration order IS the tie-break order on equal urgency (DR-19, refining DR-18):
 * follow_up_due > course_ending > medicine_changed > trend > silence — an appointment the family would otherwise
 * miss outranks a slow trend they can act on next week.
 */
public enum NudgeRule {
    FOLLOW_UP_DUE, COURSE_ENDING, MEDICINE_CHANGED, TREND, SILENCE;

    /**
     * True when the trigger is tied to a date that passes: dropping it means the family is never told about that
     * appointment or that course. Such a nudge is never discarded before its date — it sends, defers, or expires,
     * whether a cap refused it or a tie outranked it (DR-20, widening DR-19).
     */
    public boolean isDateBound() {
        return this == FOLLOW_UP_DUE || this == COURSE_ENDING;
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NudgeRule fromDbValue(String v) {
        return valueOf(v.toUpperCase(Locale.ROOT));
    }
}
