package com.elioo.baymax.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** F7: the patient's calendar. Every "today" and every day a page shows is Dhaka's (D29 adds per-recipient zones later). */
public final class PatientCalendar {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private PatientCalendar() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public static LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, ZONE);
    }
}
