package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.domain.NudgeCandidate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DR-18, enforced centrally before anything sends: highest urgency wins and the rest DROP (recorded); at most
 * {@code weeklyCap} per patient per rolling 7 days and {@code dailyCap} per zone day; outside the send window a
 * nudge is HELD to the next window's start, never dropped. Pure functions of the inputs; the service supplies
 * the counts.
 */
public final class NudgePolicy {

    private final BaymaxProperties.Nudge cfg;

    public NudgePolicy(BaymaxProperties.Nudge cfg) {
        this.cfg = cfg;
    }

    public ZoneId zone() {
        return ZoneId.of(cfg.getZone());
    }

    /** The winner and the losers, losers first-class so they can be recorded as dropped. */
    public record Selection(NudgeCandidate winner, List<NudgeCandidate> dropped) {
    }

    /** Highest urgency wins; ties go to the rule that comes first in {@code NudgeRule}'s order. */
    public Selection select(List<NudgeCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new Selection(null, List.of());
        }
        List<NudgeCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing((NudgeCandidate c) -> c.urgency().ordinal()).reversed()
                .thenComparing(c -> c.rule().ordinal()));
        return new Selection(sorted.get(0), sorted.subList(1, sorted.size()));
    }

    /** The reason the caps refuse, or null when they allow. */
    public String capReason(long countedLast7Days, long countedToday) {
        if (countedLast7Days >= cfg.getWeeklyCap()) {
            return "weekly_cap";
        }
        if (countedToday >= cfg.getDailyCap()) {
            return "daily_cap";
        }
        return null;
    }

    public boolean inWindow(Instant now) {
        int hour = now.atZone(zone()).getHour();
        return hour >= cfg.getWindowStartHour() && hour < cfg.getWindowEndHour();
    }

    /** The next window start at or after {@code now}: today's if it has not begun, else tomorrow's. */
    public Instant nextWindowStart(Instant now) {
        ZonedDateTime z = now.atZone(zone());
        ZonedDateTime start = z.with(LocalTime.of(cfg.getWindowStartHour(), 0));
        if (!z.isBefore(start)) {
            start = start.plusDays(1);
        }
        return start.toInstant();
    }

    public Instant startOfToday(Instant now) {
        return LocalDate.ofInstant(now, zone()).atStartOfDay(zone()).toInstant();
    }

    public Instant sevenDaysAgo(Instant now) {
        return now.minus(Duration.ofDays(7));
    }
}
