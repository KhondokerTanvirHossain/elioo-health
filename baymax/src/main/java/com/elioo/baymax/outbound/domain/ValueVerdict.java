package com.elioo.baymax.outbound.domain;

import java.util.Locale;
import java.util.UUID;

/**
 * F2: one shown value's verdict, from the branch of UrgencyService.assess that raised (or did not raise) the document.
 * Kinds are in severity order; a stored verdict only moves up that order (V19 trigger). UNKNOWN outranks INSIDE: it
 * withholds a reassurance the code cannot prove.
 *
 * @param observationId the observation this verdict belongs to; joined on this, never on the marker's name
 * @param side          HIGH or LOW for OUTSIDE and CRITICAL; null for INSIDE and UNKNOWN
 * @param reason        for a raising row, the exact urgency reason it raised; otherwise value_inside / value_unknown /
 *                      value_outside_no_escalation. Always ends in |stopgap or |threshold:&lt;source&gt;.
 */
public record ValueVerdict(UUID observationId, Kind kind, Urgency level, Side side, String reason) {

    /** Severity order: INSIDE &lt; UNKNOWN &lt; OUTSIDE &lt; CRITICAL. */
    public enum Kind {
        INSIDE, UNKNOWN, OUTSIDE, CRITICAL;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromDbValue(String v) {
            return valueOf(v.toUpperCase(Locale.ROOT));
        }
    }

    public enum Side {
        HIGH, LOW;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Side fromDbValue(String v) {
            return v == null ? null : valueOf(v.toUpperCase(Locale.ROOT));
        }
    }

    /** The higher of the two (this one when equal): what a store that never softens keeps. */
    public ValueVerdict max(ValueVerdict other) {
        if (other == null) {
            return this;
        }
        boolean higher = other.kind.ordinal() > kind.ordinal()
                || (other.kind == kind && other.level.ordinal() > level.ordinal());
        return higher ? other : this;
    }
}
