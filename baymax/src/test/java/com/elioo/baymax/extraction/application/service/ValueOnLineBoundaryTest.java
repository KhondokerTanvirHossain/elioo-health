package com.elioo.baymax.extraction.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a value on its named line, bounded by digits rather than by spaces.
 *
 * <p>The whole-token rule rejected every value printed with anything attached to it: "2.1 L" in one cell
 * (lab10, printed red with its flag), "80.30 L" (lab8), a value in brackets. Twelve values across batch 2
 * lost their crops and were never shown, and Tanvir's verification proved all twelve are on the pages.
 *
 * <p>The obvious relaxation — a prefix or substring match — is worse than the bug. "2.1" would hit "12.1",
 * "2.15" and "0.21", crop THAT row, and store it as the value's source. A wrong crop is not a missing crop:
 * it is a number certified by a picture of a different number, which is DR-12's 116-of-137 failure returning
 * through the fallback. So the boundary is digits: the value may not be preceded or followed by a digit or a
 * decimal point, and anything else adjacent — a unit, a flag letter, a bracket, no space at all — is fine.
 */
class ValueOnLineBoundaryTest {

    /** The raw line, as it comes off the page: boundaries are judged before normalisation flattens them. */
    private static boolean onLine(String line, String value) {
        return SpanLocator.valueAppearsOn(line, value);
    }

    @Test
    void aValueWithSomethingAttachedToItIsFound() {
        assertThat(onLine("uric acid 2.1l 3.4-7.0", "2.1")).as("lab10: flag letter in the same cell").isTrue();
        assertThat(onLine("uric acid 2.1 l 3.4-7.0", "2.1")).as("with a space").isTrue();
        assertThat(onLine("uric acid (2.1) 3.4-7.0", "2.1")).as("in brackets").isTrue();
        assertThat(onLine("haemoglobin 80.30 l 13-17", "80.30")).as("lab8: value and flag").isTrue();
        assertThat(onLine("sodium 139mmol/l 135-145", "139")).as("unit with no space").isTrue();
    }

    /** Bangla digits are folded before matching, so a Bangla-printed value behaves identically. */
    @Test
    void aBanglaDigitValueIsFound() {
        assertThat(onLine("ইউরিক অ্যাসিড ২.১ l ৩.৪-৭.০", "2.1")).isTrue();
        assertThat(onLine("uric acid 2.1 l", "২.১")).isTrue();
    }

    /**
     * The direction that matters more. Each of these is a DIFFERENT value, and matching it would crop the
     * wrong row and certify the wrong number.
     */
    @Test
    void aValueThatIsPartOfAnotherNumberIsNotFound() {
        assertThat(onLine("potassium 12.1 3.5-5.1", "2.1")).as("12.1 is not 2.1").isFalse();
        assertThat(onLine("uric acid 2.15 3.4-7.0", "2.1")).as("2.15 is not 2.1").isFalse();
        assertThat(onLine("something 2.1.3 x", "2.1")).as("2.1.3 is not 2.1").isFalse();
        assertThat(onLine("ratio 0.21 0.1-0.3", "2.1")).as("0.21 is not 2.1").isFalse();
        assertThat(onLine("count 1139 x", "139")).as("1139 is not 139").isFalse();
        assertThat(onLine("count 1390 x", "139")).as("1390 is not 139").isFalse();
    }

    @Test
    void aValueAbsentFromTheLineIsNotFound() {
        assertThat(onLine("sodium 140 135-145", "139")).isFalse();
        assertThat(onLine("", "2.1")).isFalse();
    }

    /** A qualitative result is matched the same way: "Nil" bounded by non-digits. */
    @Test
    void aWordValueIsFound() {
        assertThat(onLine("hyaline cast nil /hpf", "nil")).isTrue();
        assertThat(onLine("hyaline cast : nil", "nil")).isTrue();
    }
}
