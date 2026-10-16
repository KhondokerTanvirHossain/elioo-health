package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a printed number, explicitly rather than by stripping everything that is not a digit.
 *
 * <p>The current rule deletes every character except digits, {@code .} and {@code -}, then parses. On batch 2
 * it produced the right answer every time — and by luck, not by reading:</p>
 *
 * <pre>
 *   "80.30 L"  -> 80.30    a flag letter in the value cell (lab8)
 *   "2.1 L"    -> 2.1      (lab10)
 *   "&gt; 89"     -> 89       an eGFR reported as a bound (lab7)
 *   "6,100"    -> 6100     a thousands separator (lab9)
 *   "08"       -> 8        a leading zero (lab9)
 * </pre>
 *
 * <p><b>The luck runs out on a decimal comma.</b> A European-formatted "1,5" strips to "15" — a tenfold
 * error, silently, on a number a family is shown and a threshold is applied to. No batch-2 document contains
 * one, which is why this was recorded and not rushed; batch 3 and any imported report can.</p>
 *
 * <p>Ambiguity is refused rather than guessed. "1,234" is a thousands separator, "1,5" is a decimal comma,
 * and "1,23" is genuinely both — 1.23 in Europe, an impossible thousands group elsewhere. A value we cannot
 * read is empty, which escalates nothing and shows nothing; a value we read wrongly reaches a family.</p>
 */
class NumberParsingTest {

    private static Double parsed(String raw) {
        return UrgencyService.number(raw).orElse(null);
    }

    /** Every case batch 2 actually printed must keep working. */
    @Test
    void theCasesTheCorpusPrintsAreUnchanged() {
        assertThat(parsed("80.30 L")).as("lab8: value and flag in one cell").isEqualTo(80.30);
        assertThat(parsed("2.1 L")).as("lab10").isEqualTo(2.1);
        assertThat(parsed("2.1")).isEqualTo(2.1);
        assertThat(parsed("139")).isEqualTo(139.0);
        assertThat(parsed("08")).as("lab9: leading zero").isEqualTo(8.0);
        assertThat(parsed("12.9")).isEqualTo(12.9);
        assertThat(parsed("1.009")).as("lab2: urine specific gravity").isEqualTo(1.009);
    }

    /** A thousands separator is a grouping, not a decimal point. */
    @Test
    void aThousandsSeparatorIsRemoved() {
        assertThat(parsed("6,100")).as("lab9: white cell count").isEqualTo(6100.0);
        assertThat(parsed("1,234")).isEqualTo(1234.0);
        assertThat(parsed("1,234,567")).isEqualTo(1234567.0);
        assertThat(parsed("12,345")).isEqualTo(12345.0);
    }

    /**
     * THE CASE THE OLD RULE GETS WRONG. "1,5" is one and a half; stripping the comma makes it fifteen.
     */
    @Test
    void aDecimalCommaIsADecimalPoint() {
        assertThat(parsed("1,5")).as("a tenfold error under the strip-everything rule").isEqualTo(1.5);
        assertThat(parsed("0,9")).isEqualTo(0.9);
        assertThat(parsed("12,7")).isEqualTo(12.7);
    }

    /**
     * Genuinely ambiguous, so refused. "1,23" is 1.23 with a decimal comma and an impossible grouping
     * otherwise — but a reader cannot tell which convention the page uses from the number alone, and
     * guessing wrong is a hundredfold error in one direction.
     */
    @Test
    void anAmbiguousCommaIsRefusedRatherThanGuessed() {
        assertThat(parsed("1,23")).as("1.23 or 123? refuse").isNull();
        assertThat(parsed("45,67")).isNull();
    }

    /** A bound is not a reading. "> 89" means the true value is unknown and above 89. */
    @Test
    void aBoundIsNotAReading() {
        assertThat(parsed("> 89")).as("lab7's eGFR: the lab declined to give a number").isNull();
        assertThat(parsed(">89")).isNull();
        assertThat(parsed("< 5")).isNull();
        assertThat(parsed("<0.01")).isNull();
        assertThat(parsed("≥ 120")).isNull();
        assertThat(parsed("≤ 3")).isNull();
    }

    /** A range is not a reading either — the shape lab2 prints for microscopy. */
    @Test
    void aRangeIsNotAReading() {
        assertThat(parsed("0-2")).as("lab2: epithelial cells per HPF").isNull();
        assertThat(parsed("0-1/HPF")).isNull();
        assertThat(parsed("3.4-7.0")).isNull();
    }

    /** A negative number is a reading; a trailing or doubled minus is not. */
    @Test
    void aNegativeNumberIsRead() {
        assertThat(parsed("-1.5")).isEqualTo(-1.5);
        assertThat(parsed("-0.3")).isEqualTo(-0.3);
        assertThat(parsed("1.5-")).isNull();
        assertThat(parsed("--2")).isNull();
    }

    /** Qualitative results carry no number and must not acquire one. */
    @Test
    void aWordIsNotANumber() {
        assertThat(parsed("Nil")).isNull();
        assertThat(parsed("Negative")).isNull();
        assertThat(parsed("Normal")).isNull();
        assertThat(parsed("Straw")).isNull();
        assertThat(parsed("")).isNull();
        assertThat(parsed(null)).isNull();
    }

    /** Bangla digits are folded, as everywhere else in the pipeline. */
    @Test
    void banglaDigitsAreRead() {
        assertThat(parsed("২.১")).isEqualTo(2.1);
        assertThat(parsed("১৩৯")).isEqualTo(139.0);
        assertThat(parsed("৬,১০০")).as("Bangla digits with a thousands separator").isEqualTo(6100.0);
    }

    /**
     * A unit that contains digits must not become part of the number. The old rule turned
     * "10^3/µL" into digits and would have read a value of "5.36 10^3/µL" as something else entirely.
     */
    @Test
    void digitsInAUnitAreNotPartOfTheValue() {
        assertThat(parsed("5.36 10^3/uL")).as("the reading is 5.36; the unit's digits are not part of it")
                .isEqualTo(5.36);
        assertThat(parsed("4.29 10^6/uL")).isEqualTo(4.29);
    }

    /** More than one number in a cell is not a reading we can trust. */
    @Test
    void twoSeparateNumbersAreRefused() {
        assertThat(parsed("130 / 80")).as("a blood pressure is two readings, not one").isNull();
        assertThat(parsed("2.1 3.4")).isNull();
    }
}
