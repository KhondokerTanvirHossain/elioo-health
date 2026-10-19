package com.elioo.baymax.healthrecord.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How many more documents a family may have read this UTC month (DR-24: only DONE consumes a slot). One value feeds
 * both the add-report sheet's line and intake's refusal, so the page can never promise what intake refuses.
 *
 * @param limited false for a paying plan: no line, no cap
 * @param month   the UTC calendar month counted; the cap lifts on the first day of the next one
 */
public record DocumentAllowance(boolean limited, int max, long used, YearMonth month) {

    public static DocumentAllowance unlimited(YearMonth month) {
        return new DocumentAllowance(false, 0, 0, month);
    }

    public long remaining() {
        return limited ? Math.max(0, max - used) : Long.MAX_VALUE;
    }

    public boolean allows(int documents) {
        return !limited || documents <= remaining();
    }

    public LocalDate resetsOn() {
        return month.plusMonths(1).atDay(1);
    }
}
