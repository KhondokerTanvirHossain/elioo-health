package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-line summary under each paper on the timeline (spec §4.15): built in code from stored counts, never a model
 * summary. Counts go through UiCopy.t, so a Bangla page writes them in Bangla digits.
 */
class TimelineSummaryTest {

    private final UiCopy copy = new UiCopy();

    private static TimelineEntry entry(String type, int values, int outside, int medicines, boolean undated) {
        return new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-12"), undated, type, null, "DONE",
                values, medicines, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"), null, outside, null, null, false);
    }

    private static TimelineEntry lab(int values, int outside) {
        return entry("lab_report", values, outside, 0, false);
    }

    private static TimelineEntry rx(int medicines, boolean undated) {
        return entry("prescription", 0, 0, medicines, undated);
    }

    @Test
    void aLabLineCountsValuesAndOutsideInBanglaDigits() {
        assertThat(TimelineSummary.line(copy, Lang.BN, lab(14, 2))).isEqualTo("১৪টি মান, ২টি সীমার বাইরে");
    }

    /** Nothing outside says nothing about the range: no "০টি সীমার বাইরে", no reassurance (principle 10). */
    @Test
    void nothingOutsideMentionsNoRange() {
        assertThat(TimelineSummary.line(copy, Lang.BN, lab(9, 0))).isEqualTo("৯টি মান").doesNotContain("সীমা");
    }

    @Test
    void aPrescriptionCountsMedicinesAndAnUndatedOneSaysSo() {
        assertThat(TimelineSummary.line(copy, Lang.BN, rx(3, false))).isEqualTo("৩টি ওষুধ");
        assertThat(TimelineSummary.line(copy, Lang.BN, rx(2, true))).isEqualTo("২টি ওষুধ · কাগজে তারিখ পড়া যায়নি");
    }

    /** An imaging report reads as a lab paper; a prescription never states a value count it does not have. */
    @Test
    void imagingCountsValuesAndAPrescriptionNeverCountsValues() {
        assertThat(TimelineSummary.line(copy, Lang.BN, entry("imaging_report", 2, 1, 0, false))).isEqualTo("২টি মান, ১টি সীমার বাইরে");
        assertThat(TimelineSummary.line(copy, Lang.BN, entry("prescription", 4, 0, 1, false))).isEqualTo("১টি ওষুধ").doesNotContain("মান");
    }

    /** Any other paper: values and medicines when both are there, either alone otherwise, and nothing at all for none. */
    @Test
    void anotherPaperCountsWhatItHas() {
        assertThat(TimelineSummary.line(copy, Lang.BN, entry("discharge_summary", 5, 0, 2, false))).isEqualTo("৫টি মান · ২টি ওষুধ");
        assertThat(TimelineSummary.line(copy, Lang.BN, entry(null, 0, 0, 2, false))).isEqualTo("২টি ওষুধ");
        assertThat(TimelineSummary.line(copy, Lang.BN, entry("other", 0, 0, 0, false))).isEmpty();
        assertThat(TimelineSummary.line(copy, Lang.BN, entry("other", 0, 0, 0, true))).isEqualTo("কাগজে তারিখ পড়া যায়নি");
    }

    /** The English page: the same rule, ASCII digits. */
    @Test
    void theEnglishLineUsesAsciiDigits() {
        assertThat(TimelineSummary.line(copy, Lang.EN, lab(14, 2))).isEqualTo("14 values, 2 outside the range");
        assertThat(TimelineSummary.line(copy, Lang.EN, rx(3, true))).isEqualTo("3 medicines · date not readable on the paper");
    }
}
