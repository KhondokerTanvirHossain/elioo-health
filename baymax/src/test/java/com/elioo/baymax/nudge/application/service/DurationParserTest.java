package com.elioo.baymax.nudge.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DurationParserTest {

    @Test
    void bothScriptsAndBareNumbersAreDays() {
        assertThat(DurationParser.days("৭ দিন")).contains(7);
        assertThat(DurationParser.days("10 days")).contains(10);
        assertThat(DurationParser.days("১০")).contains(10);
        assertThat(DurationParser.days("5")).contains(5);
        assertThat(DurationParser.days("2 weeks")).contains(14);
        assertThat(DurationParser.days("২ সপ্তাহ")).contains(14);
        assertThat(DurationParser.days("1 month")).contains(30);
        assertThat(DurationParser.days("১ মাস")).contains(30);
    }

    @Test
    void unparseableIsEmptyNotAGuess() {
        assertThat(DurationParser.days(null)).isEmpty();
        assertThat(DurationParser.days("")).isEmpty();
        assertThat(DurationParser.days("continue")).isEmpty();
        assertThat(DurationParser.days("চলবে")).isEmpty();
        assertThat(DurationParser.days("3 tabs")).isEmpty();
        assertThat(DurationParser.days("0 days")).isEmpty();
    }
}
