package com.elioo.baymax.nudge.domain;

import java.util.Locale;

/**
 * What happened to a candidate. SENT and GATED count against the caps; HELD counts once it is released.
 * DEFERRED (DR-19) is a cap refusing a date-bound nudge today: it is retried on later evaluations and only
 * becomes DROPPED when its date has passed.
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
