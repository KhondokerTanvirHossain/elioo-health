package com.elioo.baymax.web.domain;

import java.util.List;

/**
 * What Medioo knows of one value's history on the value sheet (s13): the earlier readings in THIS unit ({@link EarlierReading},
 * newest first, at most {@code ResultService.EARLIER_READINGS}), and whether the same marker was also read for this patient,
 * on a finished paper before this one, in a unit that does not fold to this one (mg/dL beside µmol/L). With no reading in
 * this unit but one in another, "this is the first time" would be false, so the sheet must not say it.
 *
 * @param sameUnit          the earlier readings in the same (folded) unit
 * @param readInAnotherUnit true when at least one earlier reading of the marker is in another unit
 */
public record EarlierHistory(List<EarlierReading> sameUnit, boolean readInAnotherUnit) {

    /** No history: a value whose marker is not known, or a known marker never read before. */
    public static final EarlierHistory NONE = new EarlierHistory(List.of(), false);

    public EarlierHistory {
        sameUnit = List.copyOf(sameUnit);
    }

    /**
     * The value sheet's history line (ruling R1), the one rule both routes draw: {@code null} (no line at all) for a value
     * whose marker did not canonicalise, and for a marker read before only in another unit ("the first time" would be
     * false); {@code sheet.earlier.none} only for a known marker with no earlier reading in any unit; otherwise the
     * heading, {@code sheet.earlier} naming the unit, or {@code sheet.earlier.nounit} for a value printed without one.
     *
     * @param canonicalName the value's canonical marker name, or null when it did not canonicalise
     * @param unit          the value's printed unit, or null
     */
    public String lineKey(Object canonicalName, Object unit) {
        if (canonicalName == null) {
            return null;
        }
        if (sameUnit.isEmpty()) {
            return readInAnotherUnit ? null : "sheet.earlier.none";
        }
        return unit == null ? "sheet.earlier.nounit" : "sheet.earlier";
    }
}
