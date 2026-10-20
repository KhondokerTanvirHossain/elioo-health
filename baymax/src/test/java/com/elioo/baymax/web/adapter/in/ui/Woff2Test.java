package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader FontCoverageTest trusts, held to fontTools in both directions. FontCoverageTest only notices a code point
 * the reader leaves OUT; a reader that regressed to reporting too much (every code point of a range, gaps included)
 * would make its cmap check pass with nothing checked. So the reader must also leave out what the font does not have,
 * and its totals must equal fontTools 4.53.1's {@code len(TTFont(f).getBestCmap())} on the committed files (measured
 * 2026-10-20: 101 and 202; a rebuild that changes the subsets changes these, in the same commit as the fonts).
 */
class Woff2Test {

    private static Woff2 served(String path) {
        return Woff2.read(new WebUiTestKit().served(path));
    }

    @Test
    void theLatinCmapHasTheLettersAndNotTheArrowNorTheBanglaBlock() {
        Set<Integer> cmap = served("/assets/noto-sans-latin.woff2").codePoints();
        assertThat(cmap).as("Latin cmap holds").contains(0x41, 0x7A, 0xE9, 0x2013, 0x2212);
        assertThat(cmap).as("Latin cmap does not hold").doesNotContain(0x2192, 0x0995, 0x7F, 0x2000);
        assertThat(cmap).as("Latin cmap size, as fontTools reads it").hasSize(202);
    }

    @Test
    void theBengaliCmapHasHasantaAndTheConjunctPartsAndNotLatin() {
        Set<Integer> cmap = served("/assets/noto-sans-bengali.woff2").codePoints();
        assertThat(cmap).as("Bengali cmap holds").contains(0x0995, 0x09B7, 0x09CD, 0x0964, 0x200C, 0x25CC);
        assertThat(cmap).as("Bengali cmap does not hold").doesNotContain(0x41, 0x0984, 0x2192, 0x0900);
        assertThat(cmap).as("Bengali cmap size, as fontTools reads it").hasSize(101);
    }

    @Test
    void theWeightAxisAndTheShapingScriptsAreReadFromTheFileNotAssumed() {
        Woff2 bengali = served("/assets/noto-sans-bengali.woff2");
        assertThat(bengali.weightAxis()).containsExactly(400.0, 700.0);
        assertThat(bengali.substitutionScripts()).contains("bng2");   // its ScriptList names latn as well
        assertThat(served("/assets/noto-sans-latin.woff2").substitutionScripts()).contains("latn").doesNotContain("bng2");
    }
}
