package com.elioo.baymax.nudge.domain;

import java.util.Locale;

/** The five rules (BMX-8), in priority order: when several fire for one patient at once, the earlier wins a tie. */
public enum NudgeRule {
    TREND, MEDICINE_CHANGED, FOLLOW_UP_DUE, COURSE_ENDING, SILENCE;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NudgeRule fromDbValue(String v) {
        return valueOf(v.toUpperCase(Locale.ROOT));
    }
}
