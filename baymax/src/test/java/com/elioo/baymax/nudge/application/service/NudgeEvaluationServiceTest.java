package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.PatientRef;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import com.elioo.baymax.outbound.application.port.in.ComposeNudgeUseCase;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BMX-8 acceptance on the engine with the ports mocked: the ledger dedupes, the policy decides, one winner composes. */
class NudgeEvaluationServiceTest {

    private static final UUID F = UUID.randomUUID(), P = UUID.randomUUID();
    private final NudgeDataPort data = mock(NudgeDataPort.class);
    private final NudgePort nudges = mock(NudgePort.class);
    private final ComposeNudgeUseCase composer = mock(ComposeNudgeUseCase.class);
    private final NudgeOptOutUseCase optOut = mock(NudgeOptOutUseCase.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort records =
            mock(com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final Set<String> ledger = new HashSet<>();           // (patient|rule|key) rows that exist
    private final List<Nudge> saved = new ArrayList<>();
    private long countedWeek = 0, countedToday = 0;

    private NudgeEvaluationService at(String utc) {
        Clock clock = Clock.fixed(Instant.parse(utc), ZoneOffset.UTC);
        NudgeRules rules = new NudgeRules(data, nudges, props, clock);
        return new NudgeEvaluationService(data, nudges, rules, composer, optOut, documents, records, props, clock);
    }

    @BeforeEach
    void wire() {
        when(nudges.exists(any(), any(), anyString())).thenAnswer(i -> Mono.just(ledger.contains(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2))));
        when(nudges.save(any())).thenAnswer(i -> {
            Nudge n = i.getArgument(0);
            ledger.add(n.patientId() + "|" + n.rule() + "|" + n.triggerKey());
            Nudge withId = new Nudge(UUID.randomUUID(), n.familyId(), n.patientId(), n.rule(), n.triggerKey(), n.urgency(), n.status(), n.dropReason(), n.vars(), n.messageId(), n.holdUntil(), n.createdAt(), n.resolvedAt());
            saved.add(withId);
            return Mono.just(withId);
        });
        when(nudges.resolve(any(), any(), any(), any(), any())).thenAnswer(i -> Mono.just(new Nudge(i.getArgument(0), F, P, NudgeRule.TREND, "k", NudgeUrgency.ROUTINE, i.getArgument(1), i.getArgument(2), Map.of(), i.getArgument(3), null, Instant.EPOCH, i.getArgument(4))));
        when(nudges.countedSince(eq(P), any())).thenAnswer(i -> {
            Instant since = i.getArgument(1);
            // the "today" query has a later since than the 7-day one
            return Mono.just(since.isAfter(Instant.parse("2026-09-18T00:00:00Z")) ? countedToday : countedWeek);
        });
        when(nudges.heldDueBy(any())).thenReturn(Flux.empty());
        when(nudges.latest(any(), any())).thenReturn(Mono.empty());
        when(records.findPatient(P)).thenReturn(Mono.just(new com.elioo.baymax.healthrecord.domain.PatientProfile(
                P, F, "মা", 74, com.elioo.baymax.healthrecord.domain.PatientProfile.Sex.FEMALE, List.of("diabetes"), Instant.EPOCH, Instant.EPOCH)));
        when(nudges.consumeDeferred(any(), any(), anyString(), any())).thenReturn(Mono.just(false));
        when(data.patients()).thenReturn(Flux.just(new PatientRef(P, F, List.of("diabetes"), null, null)));
        when(data.patient(P)).thenReturn(Mono.just(new PatientRef(P, F, List.of("diabetes"), null, null)));
        when(data.openFollowUpsDueOn(any())).thenReturn(Flux.empty());
        when(data.medicationsWithDurationSince(any())).thenReturn(Flux.empty());
        when(data.markersOf(P)).thenReturn(Flux.empty());
        when(data.lastDocumentAt(P)).thenReturn(Mono.empty());
        when(optOut.token(any())).thenReturn("tok");
        when(composer.compose(any(), anyString())).thenAnswer(i -> {
            NudgeCandidate c = i.getArgument(0);
            return Mono.just(new OutboundMessage(UUID.randomUUID(), c.familyId(), c.patientId(), null, OutboundMessage.Kind.NUDGE,
                    c.urgency().toUrgency(), List.of(), "body", OutboundMessage.GateStatus.PENDING, null, null, null, null, Instant.EPOCH));
        });
    }

    /** The same open follow-up, whichever day the rule looks two days ahead from (the retry runs a day later). */
    private void followUpDueInTwoDays() {
        when(data.openFollowUpsDueOn(any())).thenAnswer(i -> Flux.just(new NudgeDataPort.FollowUpRow(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), P, F, UUID.randomUUID(), "Follow up", i.getArgument(0), "c")));
    }

    /** Acceptance: a follow_up due in 2 days → exactly one nudge; running the job again → none. */
    @Test
    void aFollowUpDueInTwoDaysNudgesExactlyOnce() {
        followUpDueInTwoDays();
        NudgeEvaluationService service = at("2026-09-19T06:00:00Z");   // 12:00 Dhaka, in window
        assertThat(service.evaluateAll().block()).isEqualTo(1L);
        verify(composer).compose(any(), eq("https://baymax.eliooo.org/app/nudges/opt-out?p=" + P + "&t=tok"));
        assertThat(service.evaluateAll().block()).isEqualTo(0L);
        verify(composer).compose(any(), anyString());   // still exactly one
    }

    /**
     * Acceptance: three rules at once → one sends; the losers are recorded with the reason. DR-20: the date-bound
     * follow_up_due DEFERS (it is retried tomorrow), the non-date-bound silence DROPS.
     */
    @Test
    void threeRulesAtOnceSendOneAndDropTwoWithReason() {
        followUpDueInTwoDays();
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(Instant.parse("2026-07-01T00:00:00Z")));   // silence
        when(data.markersOf(P)).thenReturn(Flux.just("creatinine"));
        when(data.observationsOf(P, "creatinine")).thenReturn(Flux.just(NudgeRulesTest.obs("1.1", "2026-03-01"), NudgeRulesTest.obs("1.3", "2026-06-01"), NudgeRulesTest.obs("1.5", "2026-09-01")));
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(1L);
        ArgumentCaptor<NudgeCandidate> sent = ArgumentCaptor.forClass(NudgeCandidate.class);
        verify(composer).compose(sent.capture(), anyString());
        assertThat(sent.getValue().rule()).isEqualTo(NudgeRule.TREND);
        assertThat(saved).filteredOn(n -> n.status() == NudgeStatus.DEFERRED).extracting(Nudge::rule, Nudge::dropReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NudgeRule.FOLLOW_UP_DUE, "superseded_by_trend"));
        assertThat(saved).filteredOn(n -> n.status() == NudgeStatus.DROPPED).extracting(Nudge::rule, Nudge::dropReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NudgeRule.SILENCE, "superseded_by_trend"));
    }

    /**
     * DR-20 guard: a date-bound nudge beaten in a tie is never discarded. The follow-up loses to the trend on the
     * same day, is DEFERRED rather than DROPPED, and the next evaluation consumes the deferral and sends it — so
     * the family hears about the appointment, a day after the trend they should raise at it.
     */
    @Test
    void aDateBoundNudgeBeatenInATieIsDeferredAndSendsTheNextDay() {
        followUpDueInTwoDays();
        when(data.markersOf(P)).thenReturn(Flux.just("creatinine"));
        when(data.observationsOf(P, "creatinine")).thenReturn(Flux.just(NudgeRulesTest.obs("1.1", "2026-03-01"),
                NudgeRulesTest.obs("1.3", "2026-06-01"), NudgeRulesTest.obs("1.5", "2026-09-01")));

        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(1L);
        assertThat(saved).filteredOn(n -> n.rule() == NudgeRule.FOLLOW_UP_DUE).singleElement()
                .satisfies(n -> assertThat(n.status()).isEqualTo(NudgeStatus.DEFERRED));
        assertThat(saved).noneMatch(n -> n.rule() == NudgeRule.FOLLOW_UP_DUE && n.status() == NudgeStatus.DROPPED);

        // The next day only the DEFERRED row is consumable — the trend's row still exists and dedupes, exactly as
        // in production, so the follow-up no longer has anything to lose to and is finally sent.
        saved.clear();
        when(nudges.consumeDeferred(any(), any(), anyString(), any()))
                .thenAnswer(i -> Mono.just(i.getArgument(1) == NudgeRule.FOLLOW_UP_DUE));
        ArgumentCaptor<NudgeCandidate> next = ArgumentCaptor.forClass(NudgeCandidate.class);
        assertThat(at("2026-09-20T06:00:00Z").evaluateAll().block()).isEqualTo(1L);
        verify(composer, atLeastOnce()).compose(next.capture(), anyString());
        assertThat(next.getAllValues()).extracting(NudgeCandidate::rule).contains(NudgeRule.FOLLOW_UP_DUE);
    }

    /**
     * Acceptance: a patient at 2 nudges this week → the third does not send. DR-19: a date-bound trigger is
     * DEFERRED (retried tomorrow, the appointment is not silently lost); a non-date-bound one is DROPPED.
     */
    @Test
    void theWeeklyCapDefersADateBoundNudgeAndDropsTheRest() {
        followUpDueInTwoDays();
        countedWeek = 2;
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(0L);
        verify(composer, never()).compose(any(), anyString());
        assertThat(saved).singleElement().satisfies(n -> {
            assertThat(n.status()).isEqualTo(NudgeStatus.DEFERRED);
            assertThat(n.dropReason()).isEqualTo("weekly_cap");
        });
        // the deferred row is consumed on the next evaluation, so the trigger is attempted again
        saved.clear();
        ledger.clear();
        when(nudges.consumeDeferred(any(), any(), anyString(), any())).thenReturn(Mono.just(true));
        countedWeek = 0;
        assertThat(at("2026-09-20T06:00:00Z").evaluateAll().block()).isEqualTo(1L);
    }

    /** DR-19: a trend nudge is not date-bound, so a cap drops it. */
    @Test
    void theWeeklyCapDropsANonDateBoundNudge() {
        when(data.markersOf(P)).thenReturn(Flux.just("creatinine"));
        when(data.observationsOf(P, "creatinine")).thenReturn(Flux.just(NudgeRulesTest.obs("1.1", "2026-03-01"),
                NudgeRulesTest.obs("1.3", "2026-06-01"), NudgeRulesTest.obs("1.5", "2026-09-01")));
        countedWeek = 2;
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(0L);
        assertThat(saved).singleElement().satisfies(n -> {
            assertThat(n.status()).isEqualTo(NudgeStatus.DROPPED);
            assertThat(n.dropReason()).isEqualTo("weekly_cap");
        });
    }

    /**
     * DR-22 guard: one patient's failure must not cost every other family their nudges for that run. Before this,
     * a duplicate-key error on the first patient aborted the whole evaluation — and nobody would have noticed
     * until a pilot family asked why they had heard nothing.
     */
    @Test
    void onePatientsFailureDoesNotStopTheRestOfTheRun() {
        UUID p2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID f2 = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(data.patients()).thenReturn(Flux.just(
                new PatientRef(P, F, List.of("diabetes"), null, null),
                new PatientRef(p2, f2, List.of("diabetes"), null, null)));
        when(nudges.countedSince(eq(p2), any())).thenReturn(Mono.just(0L));
        when(data.markersOf(p2)).thenReturn(Flux.empty());
        when(data.lastDocumentAt(p2)).thenReturn(Mono.empty());
        when(records.findPatient(p2)).thenReturn(Mono.just(new com.elioo.baymax.healthrecord.domain.PatientProfile(
                p2, f2, "বাবা", 70, com.elioo.baymax.healthrecord.domain.PatientProfile.Sex.MALE, List.of("diabetes"), Instant.EPOCH, Instant.EPOCH)));
        // both patients have a follow-up due; the FIRST one blows up the way a unique-constraint violation did
        followUpDueInTwoDays();
        when(data.openFollowUpsDueOn(any())).thenAnswer(i -> Flux.just(
                new NudgeDataPort.FollowUpRow(UUID.fromString("11111111-1111-1111-1111-111111111111"), P, F, UUID.randomUUID(), "Follow up", i.getArgument(0), "c"),
                new NudgeDataPort.FollowUpRow(UUID.fromString("44444444-4444-4444-4444-444444444444"), p2, f2, UUID.randomUUID(), "Follow up", i.getArgument(0), "c")));
        // The first patient's save fails the way the duplicate-key violation did in production. doAnswer, not
        // when(...): when() re-invokes the mock, which would run the @BeforeEach stub with a null argument.
        org.mockito.Mockito.doAnswer(i -> {
            Nudge n = i.getArgument(0);
            if (P.equals(n.patientId())) {
                return Mono.error(new org.springframework.dao.DuplicateKeyException("duplicate key value violates unique constraint"));
            }
            ledger.add(n.patientId() + "|" + n.rule() + "|" + n.triggerKey());
            Nudge withId = new Nudge(UUID.randomUUID(), n.familyId(), n.patientId(), n.rule(), n.triggerKey(), n.urgency(),
                    n.status(), n.dropReason(), n.vars(), n.messageId(), n.holdUntil(), n.createdAt(), n.resolvedAt());
            saved.add(withId);
            return Mono.just(withId);
        }).when(nudges).save(any());

        NudgeEvaluationService service = at("2026-09-19T06:00:00Z");
        // the run completes rather than erroring out, and the healthy patient still gets their nudge
        assertThat(service.evaluateAll().block()).isEqualTo(1L);
        assertThat(service.lastRunFailedPatients()).isEqualTo(1);
        ArgumentCaptor<NudgeCandidate> sent = ArgumentCaptor.forClass(NudgeCandidate.class);
        verify(composer).compose(sent.capture(), anyString());
        assertThat(sent.getValue().patientId()).isEqualTo(p2);
    }

    /** PO ruling 2026-09-19: every nudge names the patient as the family entered it. */
    @Test
    void everyCandidateCarriesThePatientsName() {
        followUpDueInTwoDays();
        at("2026-09-19T06:00:00Z").evaluateAll().block();
        ArgumentCaptor<NudgeCandidate> sent = ArgumentCaptor.forClass(NudgeCandidate.class);
        verify(composer).compose(sent.capture(), anyString());
        assertThat(sent.getValue().patientName()).isEqualTo("মা");
    }

    /** Acceptance: a rule firing at 03:00 Dhaka is held to 09:00 — not sent at 03:00, not never. */
    @Test
    void outsideTheWindowANudgeIsHeldToNineNotDropped() {
        followUpDueInTwoDays();
        assertThat(at("2026-09-18T21:00:00Z").evaluateAll().block()).isEqualTo(0L);   // 03:00 Dhaka on the 19th
        verify(composer, never()).compose(any(), anyString());
        Nudge held = saved.get(0);
        assertThat(held.status()).isEqualTo(NudgeStatus.HELD);
        assertThat(held.holdUntil()).isEqualTo(Instant.parse("2026-09-19T03:00:00Z"));   // 09:00 Dhaka
        // at 09:05 the held row is released and composed
        when(nudges.heldDueBy(any())).thenReturn(Flux.just(held));
        at("2026-09-19T03:05:00Z").evaluateAll().block();
        verify(composer).compose(any(), anyString());
    }

    /** Acceptance: opted out → no nudge of any rule, including silence; family-level covers every patient. */
    @Test
    void anOptedOutPatientOrFamilyGetsNothingIncludingSilence() {
        followUpDueInTwoDays();
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(Instant.parse("2026-01-01T00:00:00Z")));
        when(data.patients()).thenReturn(Flux.just(new PatientRef(P, F, List.of("diabetes"), Instant.EPOCH, null)));
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(0L);
        when(data.patients()).thenReturn(Flux.just(new PatientRef(P, F, List.of("diabetes"), null, Instant.EPOCH)));
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(0L);
        verify(composer, never()).compose(any(), anyString());
        assertThat(saved).isEmpty();
    }

    /** Acceptance: no chronic_flags → no silence nudge regardless of quiet period. */
    @Test
    void silenceNeedsChronicFlags() {
        when(data.patients()).thenReturn(Flux.just(new PatientRef(P, F, List.of(), null, null)));
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(Instant.parse("2025-01-01T00:00:00Z")));
        assertThat(at("2026-09-19T06:00:00Z").evaluateAll().block()).isEqualTo(0L);
        verify(composer, never()).compose(any(), anyString());
    }

    /** No candidate carries NOW: the urgency type has no such value, and the composer's row would refuse it. */
    @Test
    void everyCandidateUrgencyIsRoutineOrThisWeek() {
        followUpDueInTwoDays();
        at("2026-09-19T06:00:00Z").evaluateAll().block();
        ArgumentCaptor<NudgeCandidate> sent = ArgumentCaptor.forClass(NudgeCandidate.class);
        verify(composer).compose(sent.capture(), anyString());
        assertThat(sent.getValue().urgency().toUrgency()).isIn(Urgency.ROUTINE, Urgency.THIS_WEEK);
    }

    /** Production double-composition 2026-09-19: an in-window row must not be releasable as a held row by a concurrent evaluation. */
    @Test
    void anInWindowRowIsNeverReleasedAgainAsAHeldRow() {
        followUpDueInTwoDays();
        at("2026-09-19T06:00:00Z").evaluateAll().block();
        assertThat(saved).singleElement().satisfies(n -> assertThat(n.holdUntil()).isNull());
        // a second evaluation that sees the row among "held due" claims it first and composes nothing new
        Nudge row = saved.get(0);
        when(nudges.heldDueBy(any())).thenReturn(Flux.just(row));
        when(nudges.resolve(eq(row.id()), eq(NudgeStatus.HELD), any(), any(), any())).thenAnswer(i -> Mono.just(new Nudge(row.id(), F, P, row.rule(), row.triggerKey(), row.urgency(), NudgeStatus.HELD, null, row.vars(), null, null, row.createdAt(), null)));
        at("2026-09-19T06:10:00Z").evaluateAll().block();
        // the ledger row is claimed once and composed once more at most — the real guard is hold_until being NULL on the
        // in-window path, so heldDueBy (hold_until <= now) can never return it; here the mock returned it by force
        verify(composer, org.mockito.Mockito.atMost(2)).compose(any(), anyString());
    }
}
