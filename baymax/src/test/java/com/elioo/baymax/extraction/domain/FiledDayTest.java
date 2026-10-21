package com.elioo.baymax.extraction.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D2: what a filed day can be, both directions; V25's generated columns can produce only the first three. */
class FiledDayTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-20");

    @Test
    void aPrintedDateMayBeMarkedUncertainAndAnUploadDayMayBePlain() {
        assertThat(new FiledDay(DAY, false, true).uncertain()).isTrue();
        assertThat(new FiledDay(DAY, false, false).uncertain()).isFalse();
        assertThat(new FiledDay(DAY, true, false).uploadDay()).isTrue();
    }

    @Test
    void anUploadDayIsNeverMarkedUncertain() {
        assertThatThrownBy(() -> new FiledDay(DAY, true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("an upload day is never marked uncertain");
    }

    @Test
    void aFiledDayAlwaysHasADate() {
        assertThatThrownBy(() -> new FiledDay(null, true, false))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("a filed day has a date");
    }
}
