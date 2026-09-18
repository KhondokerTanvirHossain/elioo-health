package com.elioo.baymax.outbound.domain;

import java.util.Locale;

/**
 * Computed in code from the persisted extraction, never by the model (BMX-6). Ordered: a later stage may
 * raise urgency, never lower it — {@link #max} is the only combinator, and the explanation service asserts
 * the message it stores carries the urgency it was assessed at.
 */
public enum Urgency {
    ROUTINE, THIS_WEEK, NOW;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Urgency fromDbValue(String v) {
        return valueOf(v.toUpperCase(Locale.ROOT));
    }

    public boolean isAtLeast(Urgency other) {
        return ordinal() >= other.ordinal();
    }

    public static Urgency max(Urgency a, Urgency b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
