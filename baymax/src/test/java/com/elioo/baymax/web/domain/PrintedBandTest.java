package com.elioo.baymax.web.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PO ruling R3 (2026-10-22), the API half: the trend's pale band is the range the paper PRINTED for that reading and
 * nothing else. Both directions, written together: what the stored printed range yields as numbers, and every way it
 * yields none — no printed range is all three null (never a default range), and printed text the band cannot place keeps
 * its words with both numbers null.
 */
class PrintedBandTest {

    @Test
    void aTwoSidedPrintedRangeIsBothNumbersAndItsText() {
        PrintedBand band = PrintedBand.of("59", "104");
        assertThat(band.low()).isEqualTo(59.0);
        assertThat(band.high()).isEqualTo(104.0);
        assertThat(band.text()).isEqualTo("59–104");
    }

    @Test
    void decimalsAndBanglaDigitsAreRead() {
        assertThat(PrintedBand.of("3.5", "5.1")).isEqualTo(new PrintedBand(3.5, 5.1, "3.5–5.1"));
        assertThat(PrintedBand.of("৩.৫", "৫.১")).isEqualTo(new PrintedBand(3.5, 5.1, "৩.৫–৫.১"));
    }

    @Test
    void aOneSidedHighCarriesOnlyItsHigh() {
        assertThat(PrintedBand.of(null, "<200")).isEqualTo(new PrintedBand(null, 200.0, "<200"));
        assertThat(PrintedBand.of(null, "≤ 200")).isEqualTo(new PrintedBand(null, 200.0, "≤ 200"));
        assertThat(PrintedBand.of(null, "200")).isEqualTo(new PrintedBand(null, 200.0, "200"));
    }

    @Test
    void aOneSidedLowCarriesOnlyItsLow() {
        assertThat(PrintedBand.of(">40", null)).isEqualTo(new PrintedBand(40.0, null, ">40"));
        assertThat(PrintedBand.of(">= 40", null)).isEqualTo(new PrintedBand(40.0, null, ">= 40"));
        assertThat(PrintedBand.of("40", "  ")).isEqualTo(new PrintedBand(40.0, null, "40"));
    }

    /** The other direction: nothing printed is nothing sent. Never a range from a table, never a default. */
    @Test
    void noPrintedRangeIsAllThreeNull() {
        assertThat(PrintedBand.of(null, null)).isEqualTo(new PrintedBand(null, null, null));
        assertThat(PrintedBand.of("", " ")).isEqualTo(new PrintedBand(null, null, null));
        assertThat(PrintedBand.of(null, null)).isEqualTo(PrintedBand.NONE);
    }

    /**
     * Printed text the band cannot place keeps its words and sends no number: a whole range in one column, words, an
     * ambiguous comma, a bound in the wrong column ("&lt;200" as a LOW bound would draw a band above 200), a unit or a
     * qualifier, and an inverted or empty range.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "70-110     | NULL      | 70-110",
            "NULL       | 70 - 110  | 70 - 110",
            "NULL       | Up to 200 | Up to 200",
            "Negative   | NULL      | Negative",
            "NULL       | 1,23      | 1,23",
            "<200       | NULL      | <200",
            "NULL       | >40       | >40",
            "NULL       | 200 mg/dL | 200 mg/dL",
            "NULL       | see note  | see note",
    })
    void unplaceablePrintedTextKeepsItsWordsAndNoNumber(String low, String high, String text) {
        assertThat(PrintedBand.of(low, high)).isEqualTo(new PrintedBand(null, null, text));
    }

    /** A side the band cannot place spoils the other: half of a two-sided range drawn alone would misstate the paper. */
    @Test
    void oneUnplaceableSideOfATwoSidedRangeSendsNoNumberAtAll() {
        assertThat(PrintedBand.of("70", "110 (fasting)")).isEqualTo(new PrintedBand(null, null, "70–110 (fasting)"));
        assertThat(PrintedBand.of("Nil", "5")).isEqualTo(new PrintedBand(null, null, "Nil–5"));
    }

    @Test
    void anInvertedOrEmptyRangeSendsNoNumber() {
        assertThat(PrintedBand.of("104", "59")).isEqualTo(new PrintedBand(null, null, "104–59"));
        assertThat(PrintedBand.of("5", "5")).isEqualTo(new PrintedBand(null, null, "5–5"));
    }

    @Test
    void aSignedRangeIsRead() {
        assertThat(PrintedBand.of("-2", "+2")).isEqualTo(new PrintedBand(-2.0, 2.0, "-2–+2"));
    }

    @Test
    void theTextIsTheStoredStringsVerbatim() {
        assertThat(PrintedBand.of(" 59 ", "104").text()).as("only the whitespace around a stored cell is dropped").isEqualTo("59–104");
        assertThat(PrintedBand.of(null, "< 200").text()).as("never reformatted").isEqualTo("< 200");
        assertThat(PrintedBand.of("4.0", "5.60").text()).as("never re-rounded").isEqualTo("4.0–5.60");
    }
}
