package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DR-18 as pure functions. */
class NudgePolicyTest {

    private final NudgePolicy policy = new NudgePolicy(new BaymaxProperties().getNudge());
    private static final UUID F = UUID.randomUUID(), P = UUID.randomUUID();

    static NudgeCandidate c(NudgeRule rule, NudgeUrgency u) {
        return new NudgeCandidate(rule, F, P, rule.dbValue() + ":k", u, Map.of(), Set.of(), List.of());
    }

    @Test
    void highestUrgencyWinsAndTheRestAreDroppedNotQueued() {
        NudgePolicy.Selection s = policy.select(List.of(c(NudgeRule.SILENCE, NudgeUrgency.ROUTINE), c(NudgeRule.TREND, NudgeUrgency.THIS_WEEK),
                c(NudgeRule.FOLLOW_UP_DUE, NudgeUrgency.ROUTINE)));
        assertThat(s.winner().rule()).isEqualTo(NudgeRule.TREND);
        assertThat(s.dropped()).extracting(NudgeCandidate::rule).containsExactly(NudgeRule.FOLLOW_UP_DUE, NudgeRule.SILENCE);
    }

    @Test
    void capsAreTwoPerSevenDaysAndOnePerDay() {
        assertThat(policy.capReason(0, 0)).isNull();
        assertThat(policy.capReason(1, 0)).isNull();
        assertThat(policy.capReason(2, 0)).isEqualTo("weekly_cap");
        assertThat(policy.capReason(1, 1)).isEqualTo("daily_cap");
    }

    @Test
    void theSendWindowIsNineToEightDhakaAndAHoldGoesToTheNextNine() {
        ZoneId dhaka = ZoneId.of("Asia/Dhaka");
        Instant three = Instant.parse("2026-09-18T21:00:00Z");     // 03:00 Dhaka next day
        assertThat(policy.inWindow(three)).isFalse();
        assertThat(policy.nextWindowStart(three).atZone(dhaka).toString()).startsWith("2026-09-19T09:00");
        Instant ten = Instant.parse("2026-09-19T04:00:00Z");       // 10:00 Dhaka
        assertThat(policy.inWindow(ten)).isTrue();
        Instant twentyOne = Instant.parse("2026-09-19T15:00:00Z"); // 21:00 Dhaka
        assertThat(policy.inWindow(twentyOne)).isFalse();
        assertThat(policy.nextWindowStart(twentyOne).atZone(dhaka).toString()).startsWith("2026-09-20T09:00");
        assertThat(policy.inWindow(Instant.parse("2026-09-19T14:00:00Z"))).isFalse();   // 20:00 is outside
    }

    /** DR-18 at the type level: a nudge urgency can only ever map to ROUTINE or THIS_WEEK. */
    @Test
    void aNudgeCanNeverBeNow() {
        for (NudgeUrgency u : NudgeUrgency.values()) {
            assertThat(u.toUrgency()).isNotEqualTo(Urgency.NOW);
        }
        assertThat(NudgeUrgency.values()).hasSize(2);
    }
}
