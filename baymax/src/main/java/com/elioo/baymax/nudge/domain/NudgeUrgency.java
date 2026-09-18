package com.elioo.baymax.nudge.domain;

import com.elioo.baymax.outbound.domain.Urgency;

/**
 * A nudge is ROUTINE or THIS_WEEK. NOW does not exist in this type (DR-18): an unprompted NOW about old data would
 * terrify rather than help; a genuine emergency comes from a fresh document. The database CHECK on nudge.urgency
 * says the same.
 */
public enum NudgeUrgency {
    ROUTINE(Urgency.ROUTINE), THIS_WEEK(Urgency.THIS_WEEK);

    private final Urgency urgency;

    NudgeUrgency(Urgency urgency) {
        this.urgency = urgency;
    }

    public Urgency toUrgency() {
        return urgency;
    }

    public boolean isHigherThan(NudgeUrgency other) {
        return ordinal() > other.ordinal();
    }
}
