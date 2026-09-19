package com.elioo.baymax.nudge.domain;

import java.util.Locale;

/**
 * What happened to a candidate. SENT and GATED count against the caps; HELD counts once it is released.
 * DEFERRED (DR-19, widened by DR-20) is a date-bound nudge that could not go today — refused by a cap or beaten
 * in a tie: it is retried on later evaluations and only becomes DROPPED when its date has passed.
 */
public enum NudgeStatus {
    SENT, GATED, HELD, DEFERRED, DROPPED, FAILED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NudgeStatus fromDbValue(String v) {
        return valueOf(v.toUpperCase(Locale.ROOT));
    }
}
