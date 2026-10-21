package com.elioo.baymax.outbound.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6: one unit fold for the module. The স্বাস্থ্য series key folds a unit with {@link MarkerThreshold#foldUnit}, and
 * the thresholds and the value sheet's earlier readings compare with {@link MarkerThreshold#sameUnit}; the two must never
 * tell different stories, or a series would join readings a threshold keeps apart (or the reverse).
 */
class MarkerThresholdTest {

    /** The spellings the fold exists for (UrgencyServiceTest's, ValueVerdictTest's and the batch-2 scorer's), and two it must keep apart. */
    private static final List<String> UNITS = List.of(
            "µmol/L",        // MICRO SIGN, as labs print it
            "μmol/L",        // GREEK SMALL LETTER MU, which NFKC makes of it
            "umol/L",             // ASCII u, as a hand-edited properties file says it
            "UMOL/L",
            "mmol/L", "mmol/l", " mmol / L ", "MMOL/L",
            "mg/dL", "mg/dl",
            "10^3/µL", "10^3/uL",
            "%", "mmol/mol", "",
            "mIU/L");

    @Test
    void foldUnitIsTheSameUnitsFold() {
        int equalPairs = 0;
        for (String a : UNITS) {
            for (String b : UNITS) {
                boolean same = MarkerThreshold.sameUnit(a, b);
                assertThat(MarkerThreshold.foldUnit(a).equals(MarkerThreshold.foldUnit(b)))
                        .as("foldUnit(%s) vs foldUnit(%s) agrees with sameUnit", a, b).isEqualTo(same);
                if (same && !a.equals(b)) {
                    equalPairs++;
                }
            }
        }
        // not vacuous: the list really holds spellings the fold joins, and units it keeps apart
        assertThat(equalPairs).as("distinct spellings sameUnit treats as one").isGreaterThan(10);
        assertThat(MarkerThreshold.foldUnit("µmol/L")).isEqualTo(MarkerThreshold.foldUnit("umol/L"));
        assertThat(MarkerThreshold.foldUnit("μmol/L")).isEqualTo(MarkerThreshold.foldUnit("umol/L"));
        assertThat(MarkerThreshold.foldUnit("mg/dL")).isNotEqualTo(MarkerThreshold.foldUnit("mmol/L"));
        assertThat(MarkerThreshold.foldUnit("%")).isNotEqualTo(MarkerThreshold.foldUnit("mmol/mol"));
        assertThat(MarkerThreshold.foldUnit(null)).isEqualTo(MarkerThreshold.foldUnit(""));
    }
}
