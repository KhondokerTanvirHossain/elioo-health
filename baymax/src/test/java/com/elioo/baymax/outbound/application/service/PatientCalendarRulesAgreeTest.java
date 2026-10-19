package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7: the patient's calendar is Dhaka's. Every rule that asks "is this date before today" asks PatientCalendar. The
 * sweep's SQL predicate is held to this rule at the same three instants on Postgres, where its container already runs:
 * {@code DocumentUrgencyNeverLowersTest.theSweepsPredicateAndTheRuleAgreeOnTheDhakaDay}. C4 adds the attention stack's
 * window here.
 */
class PatientCalendarRulesAgreeTest {

    @ParameterizedTest(name = "at {0} a follow-up due 2026-10-19 is past due: {1}")
    @CsvSource({"2026-10-19T17:59:00Z,false",     // 23:59 on the 19th in Dhaka: due today, not past
                "2026-10-19T18:00:00Z,true",      // 00:00 on the 20th in Dhaka
                "2026-10-19T23:30:00Z,true"})     // 05:30 on the 20th in Dhaka — still the 19th in UTC
    void theFollowUpRuleJudgesPastDueOnTheDhakaDay(String at, boolean pastDue) {
        UrgencyService rule = new UrgencyService(new BaymaxProperties(), Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
        var facts = UrgencyServiceTest.facts(List.of(), List.of(Map.of("instruction", "আবার আসবেন", "due_date", "2026-10-19")), Map.of(), "{}");
        var assessed = rule.assess(facts);
        assertThat(assessed.level()).isEqualTo(pastDue ? Urgency.THIS_WEEK : Urgency.ROUTINE);
        // the path, not just the verdict: THIS_WEEK from the follow-up rule, and from no other
        if (pastDue) {
            assertThat(assessed.reasons()).containsExactly("follow_up_past_due");
        } else {
            assertThat(assessed.reasons()).isEmpty();
        }
    }
}
