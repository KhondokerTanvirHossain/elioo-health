package com.elioo.baymax.web.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PO ruling R3 (2026-10-22, DR-48), the API half: the trend's pale band is the range the paper PRINTED for that reading and
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
    }

    @Test
    void aOneSidedLowCarriesOnlyItsLow() {
        assertThat(PrintedBand.of(">40", null)).isEqualTo(new PrintedBand(40.0, null, ">40"));
        assertThat(PrintedBand.of(">= 40", "  ")).isEqualTo(new PrintedBand(40.0, null, ">= 40"));
    }

    /**
     * A lone signed bound is placed by its sign, not by the column extraction happened to store it in: the extraction
     * prompt and schema give no rule for which column a one-sided range goes in, and the sign is what the paper printed.
     * "&lt;200" stored as a LOW is still an upper bound of 200; read as a low it would draw a band above 200.
     */
    @Test
    void aLoneSignedBoundIsPlacedByItsSignWhicheverColumnHoldsIt() {
        assertThat(PrintedBand.of("<200", null)).isEqualTo(new PrintedBand(null, 200.0, "<200"));
        assertThat(PrintedBand.of("≤ 200", " ")).isEqualTo(new PrintedBand(null, 200.0, "≤ 200"));
        assertThat(PrintedBand.of(null, ">40")).isEqualTo(new PrintedBand(40.0, null, ">40"));
        assertThat(PrintedBand.of(null, ">= 40")).isEqualTo(new PrintedBand(40.0, null, ">= 40"));
    }

    /**
     * The other direction of the same rule: a lone bare number says nothing about its side except the column it was
     * stored in, which nothing checks. "&lt;200" stored as a bare low "200" would draw a band above 200 — the wrong band
     * R3 exists to prevent — so a lone bare number is text with no number, in either column.
     */
    @Test
    void aLoneBareNumberIsTextWithNoNumberInEitherColumn() {
        assertThat(PrintedBand.of("200", null)).isEqualTo(new PrintedBand(null, null, "200"));
        assertThat(PrintedBand.of(null, "200")).isEqualTo(new PrintedBand(null, null, "200"));
        assertThat(PrintedBand.of("40", "  ")).isEqualTo(new PrintedBand(null, null, "40"));
        assertThat(PrintedBand.of(" ", "৪০")).isEqualTo(new PrintedBand(null, null, "৪০"));
    }

    /** Beside a filled other column the columns are a two-sided range, and a sign facing against its column is refused. */
    @Test
    void aSignContradictingAFilledOtherColumnSendsNoNumber() {
        assertThat(PrintedBand.of("<200", "250")).isEqualTo(new PrintedBand(null, null, "<200–250"));
        assertThat(PrintedBand.of("40", ">100")).isEqualTo(new PrintedBand(null, null, "40–>100"));
        assertThat(PrintedBand.of(">40", "<200")).as("signs facing their own columns are a two-sided range")
                .isEqualTo(new PrintedBand(40.0, 200.0, ">40–<200"));
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
     * ambiguous comma (bare or behind a sign), a unit or a qualifier.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "70-110     | NULL      | 70-110",
            "NULL       | 70 - 110  | 70 - 110",
            "NULL       | Up to 200 | Up to 200",
            "Negative   | NULL      | Negative",
            "NULL       | 1,23      | 1,23",
            "NULL       | <1,23     | <1,23",
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
