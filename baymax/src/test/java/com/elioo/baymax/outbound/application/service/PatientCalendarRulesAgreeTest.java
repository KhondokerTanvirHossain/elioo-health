package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.application.service.AttentionService;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F7: the patient's calendar is Dhaka's. Every rule that asks "is this date before today" asks PatientCalendar. The
 * sweep's SQL predicate is held to this rule at the same three instants on Postgres, where its container already runs:
 * {@code DocumentUrgencyNeverLowersTest.theSweepsPredicateAndTheRuleAgreeOnTheDhakaDay}. C4 adds the attention stack's
 * window here: the stack's follow-up window and the urgency rule hand a paper to each other at the same Dhaka midnight.
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

    /**
     * C4: a follow-up due 2026-10-19 on a paper is on the চ্যাট stack at every instant — as the doctor's date while it is
     * today or later (the window starts on the patient's today), and as the THIS_WEEK paper once the rule calls it past
     * due — never neither. The paper's level is what the rule says at that instant; the read model returns it as urgent
     * exactly when that level has a line; the follow-up read honours the window it is asked for.
     */
    @ParameterizedTest(name = "at {0} the paper is on the stack as {1}")
    @CsvSource({"2026-10-19T17:59:00Z,FollowUp",      // 23:59 on the 19th in Dhaka: due today, the doctor's date
                "2026-10-19T18:00:00Z,Urgent",        // 00:00 on the 20th in Dhaka: past due, THIS_WEEK
                "2026-10-19T23:30:00Z,Urgent"})       // 05:30 on the 20th in Dhaka — still the 19th in UTC
    void theAttentionWindowAndTheUrgencyRuleHandThePaperOverAtTheSameMidnight(String at, String card) {
        Clock clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC);
        LocalDate due = LocalDate.parse("2026-10-19");
        UUID family = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID paper = UUID.randomUUID();
        Urgency level = new UrgencyService(new BaymaxProperties(), clock).assess(UrgencyServiceTest.facts(List.of(),
                List.of(Map.of("instruction", "আবার আসবেন", "due_date", due.toString())), Map.of(), "{}")).level();

        HealthRecordPort records = mock(HealthRecordPort.class);
        TimelineReadPort timeline = mock(TimelineReadPort.class);
        NudgeDataPort nudgeData = mock(NudgeDataPort.class);
        when(records.visiblePatient(family, patient)).thenReturn(reactor.core.publisher.Mono.just(new PatientAccess(
                new PatientProfile(patient, family, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), Instant.EPOCH, Instant.EPOCH), true)));
        TimelineEntry entry = new TimelineEntry(paper, LocalDate.parse("2026-10-12"), false, "prescription", null, "DONE", 0, 1, 1, 0,
                "k", Instant.parse("2026-10-12T08:00:00Z"), level, 0, null, null);
        when(timeline.attention(eq(patient), any(), anyInt())).thenReturn(level == Urgency.ROUTINE
                ? reactor.core.publisher.Flux.empty() : reactor.core.publisher.Flux.just(entry));
        when(timeline.inFlightAndRetakes(eq(patient), any())).thenReturn(reactor.core.publisher.Flux.empty());
        when(nudgeData.openFollowUpsOfPatient(eq(patient), any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(1);
            LocalDate to = call.getArgument(2);
            return due.isBefore(from) || due.isAfter(to) ? reactor.core.publisher.Flux.empty()
                    : reactor.core.publisher.Flux.just(new NudgeDataPort.FollowUpRow(UUID.randomUUID(), patient, family, paper, "আবার আসবেন", due, "k"));
        });

        List<AttentionItem> stack = new AttentionService(records, timeline, nudgeData, clock).attention(family, patient).block();
        assertThat(stack).as("the paper vanished from the stack between the follow-up window and the urgency rule")
                .extracting(AttentionItem::documentId).contains(paper);
        assertThat(stack).as("the card it is on").extracting(i -> i.getClass().getSimpleName()).containsExactly(card);
    }
}
