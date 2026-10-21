package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec §4.6: what needs attention, in code-urgency order, from stored F1 levels — never from a message row. */
class AttentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T04:00:00Z");   // 10:00 in Dhaka
    private static final LocalDate TODAY = LocalDate.parse("2026-10-19");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID MA = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final TimelineReadPort timeline = mock(TimelineReadPort.class);
    private final NudgeDataPort nudgeData = mock(NudgeDataPort.class);
    private AttentionService service;

    @BeforeEach
    void wire() {
        service = at(NOW);
        when(records.visiblePatient(FAMILY, MA)).thenReturn(Mono.just(new PatientAccess(
                new PatientProfile(MA, FAMILY, "আম্মা", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), true)));
        when(timeline.attention(eq(MA), any(), anyInt())).thenReturn(Flux.empty());
        when(timeline.inFlightAndRetakes(eq(MA), any())).thenReturn(Flux.empty());
        when(nudgeData.openFollowUpsOfPatient(eq(MA), any(), any())).thenReturn(Flux.empty());
    }

    private AttentionService at(Instant instant) {
        return new AttentionService(records, timeline, nudgeData, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static TimelineEntry entry(String status, Urgency level, Duration ago) {
        return entry(status, level, ago, TODAY.minusDays(3));
    }

    private static TimelineEntry entry(String status, Urgency level, Duration ago, LocalDate date) {
        return new TimelineEntry(UUID.randomUUID(), date, false, "lab_report", "Shapla", status, 14, 0, 0, 0, "k",
                NOW.minus(ago), level, 0, null, null, false);
    }

    @Test
    void theStackIsNowThenThisWeekThenRetakeThenFollowUpThenReading() {
        TimelineEntry nowNew = entry("DONE", Urgency.NOW, Duration.ofDays(1));
        TimelineEntry nowOld = entry("DONE", Urgency.NOW, Duration.ofDays(9));
        TimelineEntry week = entry("DONE", Urgency.THIS_WEEK, Duration.ofDays(5));
        TimelineEntry retake = entry("NEEDS_RETAKE", null, Duration.ofDays(2));
        TimelineEntry reading = entry("PROCESSING", null, Duration.ofMinutes(3));
        // each read hands its rows in an order the stack must not keep: the service orders, not the arrival
        when(timeline.attention(eq(MA), eq(NOW.minus(Duration.ofDays(30))), anyInt())).thenReturn(Flux.just(week, nowOld, nowNew));
        when(timeline.inFlightAndRetakes(eq(MA), eq(NOW.minus(Duration.ofDays(30))))).thenReturn(Flux.just(reading, retake));
        UUID rx = UUID.randomUUID();
        when(nudgeData.openFollowUpsOfPatient(MA, TODAY, TODAY.plusDays(7))).thenReturn(Flux.just(
                new NudgeDataPort.FollowUpRow(UUID.randomUUID(), MA, FAMILY, rx, "CBC after 7 days", TODAY.plusDays(3), "k3"),
                new NudgeDataPort.FollowUpRow(UUID.randomUUID(), MA, FAMILY, rx, "Next visit: 20/10/26", TODAY.plusDays(1), "k1")));

        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> {
            assertThat(stack).extracting(AttentionItem::documentId)
                    .containsExactly(nowNew.documentId(), nowOld.documentId(), week.documentId(), retake.documentId(), rx, rx, reading.documentId());
            assertThat(stack.get(3)).isInstanceOf(AttentionItem.Retake.class);
            assertThat(stack.get(6)).isInstanceOf(AttentionItem.Reading.class);
            AttentionItem.FollowUp soonest = (AttentionItem.FollowUp) stack.get(4);
            assertThat(soonest.instruction()).isEqualTo("Next visit: 20/10/26");
            assertThat(soonest.dueDate()).isEqualTo(TODAY.plusDays(1));
            assertThat(soonest.cropKey()).isEqualTo("k1");
        }).verifyComplete();
    }

    /**
     * The urgent rows are asked for with the stack's own limit, ten: a limit of one would hide every other NOW paper and
     * every THIS_WEEK one behind the newest NOW (the literal here, not the constant, so a changed constant is caught).
     */
    @Test
    void theUrgentPapersAreAskedForUpToTen() {
        StepVerifier.create(service.attention(FAMILY, MA)).expectNextCount(1).verifyComplete();
        verify(timeline).attention(MA, NOW.minus(Duration.ofDays(30)), 10);
    }

    /**
     * The কাগজপত্র strip reads only what it draws: the urgent papers, in the stack's order, after visibility — never the
     * papers being read or the doctor's dates, which it throws away (two queries per load, every 5 s while one is read).
     */
    @Test
    void theStripReadsOnlyTheUrgentPapersInTheStacksOrder() {
        TimelineEntry week = entry("DONE", Urgency.THIS_WEEK, Duration.ofDays(1));
        TimelineEntry now = entry("DONE", Urgency.NOW, Duration.ofDays(5));
        when(timeline.attention(eq(MA), eq(NOW.minus(Duration.ofDays(30))), eq(10))).thenReturn(Flux.just(week, now));
        StepVerifier.create(service.urgent(FAMILY, MA)).assertNext(strip -> {
            assertThat(strip).extracting(AttentionItem::documentId).containsExactly(now.documentId(), week.documentId());
            assertThat(strip).allSatisfy(u -> assertThat(u).isInstanceOf(AttentionItem.Urgent.class));
        }).verifyComplete();
        verify(timeline, never()).inFlightAndRetakes(any(), any());
        verify(nudgeData, never()).openFollowUpsOfPatient(any(), any(), any());
    }

    @Test
    void theStripOfAPatientThisFamilyCannotSeeIs404AndNothingIsRead() {
        UUID stranger = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, stranger)).thenReturn(Mono.empty());
        StepVerifier.create(service.urgent(FAMILY, stranger))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404).verify();
        verifyNoInteractions(timeline, nudgeData);
    }

    /**
     * Within a level the read model's order holds: the newer document date first, whatever the upload order (C2's
     * withinALevelTheNewerDocumentDateComesFirst), so the stack and the কাগজপত্র strip never disagree.
     */
    @Test
    void withinALevelTheNewerDocumentDateComesFirstEvenWhenItWasSentEarlier() {
        TimelineEntry newerPaperSentFirst = entry("DONE", Urgency.NOW, Duration.ofDays(9), TODAY.minusDays(10));
        TimelineEntry olderPaperSentLater = entry("DONE", Urgency.NOW, Duration.ofDays(1), TODAY.minusDays(20));
        when(timeline.attention(eq(MA), any(), anyInt())).thenReturn(Flux.just(olderPaperSentLater, newerPaperSentFirst));
        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> assertThat(stack).extracting(AttentionItem::documentId)
                .containsExactly(newerPaperSentFirst.documentId(), olderPaperSentLater.documentId())).verifyComplete();
    }

    /** The service never re-filters by level or reads a message: what the read model calls urgent is the stack. */
    @Test
    void theUrgentCardsAreExactlyTheReadModelsAttentionRows() {
        TimelineEntry week = entry("DONE", Urgency.THIS_WEEK, Duration.ofDays(5));
        when(timeline.attention(eq(MA), any(), anyInt())).thenReturn(Flux.just(week));
        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> assertThat(stack).singleElement()
                .isInstanceOfSatisfying(AttentionItem.Urgent.class, u -> {
                    assertThat(u.documentId()).isEqualTo(week.documentId());
                    assertThat(u.level()).isEqualTo(Urgency.THIS_WEEK);
                    assertThat(u.date()).isEqualTo(week.date());
                    assertThat(u.documentType()).isEqualTo("lab_report");
                })).verifyComplete();
    }

    /**
     * D1: a retake card and a reading card carry the day the paper is filed under and whether that is the upload day, as
     * the read model gives them — never re-derived here — beside the upload instant they always had.
     */
    @Test
    void retakeAndReadingCardsCarryTheFiledDate() {
        // dates that are NOT the upload day in UTC or in Dhaka, so a card that derived its date from the instant fails here
        TimelineEntry retake = new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-11"), true, "lab_report", null, "NEEDS_RETAKE",
                0, 0, 0, 0, "k", NOW.minus(Duration.ofDays(2)), null, 0, null, "low_overall", false);
        TimelineEntry reading = new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-09-30"), false, "lab_report", null, "PROCESSING",
                0, 0, 0, 0, "k", NOW.minus(Duration.ofMinutes(3)), null, 0, null, null, false);
        when(timeline.inFlightAndRetakes(eq(MA), any())).thenReturn(Flux.just(retake, reading));
        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> {
            assertThat(stack).hasSize(2);
            assertThat(stack.get(0)).isInstanceOfSatisfying(AttentionItem.Retake.class, r -> {
                assertThat(r.documentId()).isEqualTo(retake.documentId());
                assertThat(r.date()).isEqualTo(LocalDate.parse("2026-10-11"));
                assertThat(r.dateIsFallback()).isTrue();
                assertThat(r.receivedAt()).isEqualTo(retake.createdAt());
            });
            assertThat(stack.get(1)).isInstanceOfSatisfying(AttentionItem.Reading.class, r -> {
                assertThat(r.documentId()).isEqualTo(reading.documentId());
                assertThat(r.date()).isEqualTo(LocalDate.parse("2026-09-30"));
                assertThat(r.dateIsFallback()).isFalse();
                assertThat(r.receivedAt()).isEqualTo(reading.createdAt());
            });
        }).verifyComplete();
    }

    /** Nothing needs attention: an empty stack, not an error and not a placeholder card. */
    @Test
    void nothingToAttendToIsAnEmptyStack() {
        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> assertThat(stack).isEmpty()).verifyComplete();
    }

    /**
     * "Today" is the patient's calendar: at 00:30 in Dhaka on the 20th (still the 19th in UTC) the doctor's dates are
     * read from the 20th to the 27th, never from the 19th.
     */
    @Test
    void theFollowUpWindowStartsOnThePatientsToday() {
        Instant justAfterMidnightInDhaka = Instant.parse("2026-10-19T18:30:00Z");
        StepVerifier.create(at(justAfterMidnightInDhaka).attention(FAMILY, MA)).expectNextCount(1).verifyComplete();
        verify(nudgeData).openFollowUpsOfPatient(MA, LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-27"));
        verify(timeline).attention(eq(MA), eq(justAfterMidnightInDhaka.minus(Duration.ofDays(30))), anyInt());
        verify(timeline).inFlightAndRetakes(MA, justAfterMidnightInDhaka.minus(Duration.ofDays(30)));
    }

    @Test
    void aPatientThisFamilyCannotSeeIs404AndNothingIsRead() {
        UUID stranger = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, stranger)).thenReturn(Mono.empty());
        StepVerifier.create(service.attention(FAMILY, stranger))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404).verify();
        verifyNoInteractions(timeline, nudgeData);
    }

    /** D27 in the domain: an urgent card is NOW or THIS_WEEK; asking for one at ROUTINE or unassessed is loud. */
    @Test
    void anUrgentCardRefusesALevelThatHasNoLine() {
        for (Urgency none : new Urgency[]{Urgency.ROUTINE, null}) {
            assertThatThrownBy(() -> new AttentionItem.Urgent(UUID.randomUUID(), none, "lab_report", TODAY, false, NOW))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOW or THIS_WEEK");
        }
    }
}
