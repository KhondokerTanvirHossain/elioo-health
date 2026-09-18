package com.elioo.baymax.nudge.domain;

import java.util.Locale;

/** What happened to a candidate. SENT and GATED count against the caps; HELD counts once it is released. */
public enum NudgeStatus {
    SENT, GATED, HELD, DROPPED, FAILED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NudgeStatus fromDbValue(String v) {
        return valueOf(v.toUpperCase(Locale.ROOT));
    }
}
