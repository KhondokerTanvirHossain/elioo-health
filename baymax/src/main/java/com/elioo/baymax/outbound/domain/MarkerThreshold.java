package com.elioo.baymax.outbound.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * One marker's urgency thresholds, as a clinician would state them: per marker, per unit, absolute numbers.
 *
 * <p>This replaces nothing yet. The live rule is still the 2×/0.5× stopgap, which batch 2 showed to be wrong
 * in both directions — it escalates ESR 29 and a dilute urine sample to NOW/THIS_WEEK, and it lands
 * potassium 6.4 at THIS_WEEK when published critical-value lists put it at same-day. No entry here affects
 * any family's message until a doctor signs it off and its {@link Status} becomes {@code ACTIVE}.</p>
 *
 * <p><b>Units are part of the key, never converted.</b> Creatinine 65 µmol/L is normal and creatinine 65
 * mg/dL is not survivable; a threshold table that compares across units is a way to kill someone. A value
 * whose unit does not match an entry falls back to the stopgap and says so.</p>
 *
 * @param canonicalName  the marker as {@code MarkerMatcher} canonicalises it — the join key to a value
 * @param unit           the unit these numbers are stated in, compared case- and space-insensitively
 * @param nowLow         at or below this → GO NOW; null when no low bound is critical
 * @param nowHigh        at or above this → GO NOW; null when no high bound is critical
 * @param weekLow        at or below this → THIS WEEK; null when no low bound warrants it
 * @param weekHigh       at or above this → THIS WEEK; null when no high bound warrants it
 * @param neverEscalates this marker alone never raises urgency, whatever the number — a chronic risk marker
 *                       whose signal is a trend, not a single reading (lipids, DR-17)
 * @param direction      which way is bad; {@link Direction#HIGH_IS_GOOD} inverts the reading for HDL, where
 *                       a high value is protective and treating it as an upper limit escalates health
 * @param status         {@code PROPOSED} until a doctor signs it off. Only {@code ACTIVE} can change a
 *                       message, and a test asserts that.
 * @param symptomGated   this marker's tiers apply only when the patient is currently unwell. A hook: no
 *                       symptom question exists yet, so an entry with this set is inert regardless of status.
 * @param source         where the number came from, e.g. "RCPath 2023" — the doctor reviews the citation
 */
public record MarkerThreshold(
        String canonicalName,
        String unit,
        Double nowLow,
        Double nowHigh,
        Double weekLow,
        Double weekHigh,
        boolean neverEscalates,
        Direction direction,
        Status status,
        boolean symptomGated,
        String source) {

    /** Which end of the range is dangerous. */
    public enum Direction {
        /** The normal case: too high or too low is bad. */
        NORMAL,
        /** HDL and the like: a high value is protective, so only the low end escalates. */
        HIGH_IS_GOOD,
        /** eGFR and the like: only the low end is dangerous. */
        LOW_IS_BAD
    }

    /** Whether a clinician has signed this entry off. Nothing but ACTIVE may change a message. */
    public enum Status {
        PROPOSED, ACTIVE
    }

    public MarkerThreshold {
        direction = direction == null ? Direction.NORMAL : direction;
        status = status == null ? Status.PROPOSED : status;
    }

    /** True only for an entry a doctor has signed off AND that carries no hook still awaiting design. */
    public boolean isLive() {
        return status == Status.ACTIVE && !symptomGated;
    }

    public boolean matches(String canonical, String valueUnit) {
        return sameToken(canonicalName, canonical) && sameUnit(unit, valueUnit);
    }

    /**
     * Units match on a folded comparison, never a conversion. "mmol/L", "mmol/l" and " mmol / L " are one
     * unit; "mg/dL" and "µmol/L" are two, and a marker stated in one never answers for the other.
     */
    static boolean sameUnit(String a, String b) {
        return fold(a).equals(fold(b));
    }

    private static boolean sameToken(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String fold(String s) {
        if (s == null) {
            return "";
        }
        // NFKC folds MICRO SIGN (U+00B5) onto GREEK SMALL LETTER MU (U+03BC): the same defect that
        // manufactured eight false unit misses in the batch-2 scorer.
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Where a reading sits under THIS entry, honouring direction. Empty when the entry says nothing about
     * this reading — the caller then falls back to the stopgap.
     */
    public Optional<Urgency> assess(double value) {
        if (neverEscalates) {
            return Optional.of(Urgency.ROUTINE);
        }
        boolean lowMatters = direction != Direction.HIGH_IS_GOOD;
        boolean highMatters = direction != Direction.LOW_IS_BAD && direction != Direction.HIGH_IS_GOOD;
        // HIGH_IS_GOOD inverts: for HDL the LOW end is what matters, and it is stated in the low bounds.
        if (direction == Direction.HIGH_IS_GOOD) {
            lowMatters = true;
        }

        if (lowMatters && nowLow != null && value <= nowLow) {
            return Optional.of(Urgency.NOW);
        }
        if (highMatters && nowHigh != null && value >= nowHigh) {
            return Optional.of(Urgency.NOW);
        }
        if (lowMatters && weekLow != null && value <= weekLow) {
            return Optional.of(Urgency.THIS_WEEK);
        }
        if (highMatters && weekHigh != null && value >= weekHigh) {
            return Optional.of(Urgency.THIS_WEEK);
        }
        return Optional.of(Urgency.ROUTINE);
    }
}
