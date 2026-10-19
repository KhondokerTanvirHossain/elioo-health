package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A time of day on the patient's calendar (Dhaka): "সকাল ১০:১২" on a Bangla page, "10:12" on an English one. */
class UiCopyTimeTest {

    private final UiCopy copy = new UiCopy();

    @ParameterizedTest(name = "{0} UTC → {1}")
    @CsvSource({"2026-10-20T04:12:00Z,সকাল ১০:১২,10:12", "2026-10-19T23:00:00Z,ভোর ৫:০০,05:00",
            "2026-10-20T06:00:00Z,দুপুর ১২:০০,12:00", "2026-10-20T09:30:00Z,বিকাল ৩:৩০,15:30",
            "2026-10-20T11:15:00Z,সন্ধ্যা ৫:১৫,17:15", "2026-10-20T13:00:00Z,রাত ৭:০০,19:00",
            "2026-10-19T18:00:00Z,রাত ১২:০০,00:00", "2026-10-19T21:59:00Z,রাত ৩:৫৯,03:59",
            "2026-10-19T22:00:00Z,ভোর ৪:০০,04:00", "2026-10-20T05:59:00Z,সকাল ১১:৫৯,11:59"})
    void theTimeIsDhakasWithThePartOfDayInBangla(String utc, String bn, String en) {
        assertThat(copy.time(Lang.BN, Instant.parse(utc))).isEqualTo(bn);
        assertThat(copy.time(Lang.EN, Instant.parse(utc))).isEqualTo(en);
    }
}
