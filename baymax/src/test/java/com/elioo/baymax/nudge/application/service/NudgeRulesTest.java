package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.MedicationRow;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.ObservationRow;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.PatientRef;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The five rules on stored rows. No model anywhere. */
class NudgeRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-19T06:00:00Z");   // 12:00 Dhaka
    private static final UUID F = UUID.randomUUID(), P = UUID.randomUUID();
    private final NudgeDataPort data = mock(NudgeDataPort.class);
    private final NudgePort nudges = mock(NudgePort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final NudgeRules rules = new NudgeRules(data, nudges, props, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PatientRef chronic = new PatientRef(P, F, List.of("diabetes"), null, null);
    private final PatientRef noFlags = new PatientRef(P, F, List.of(), null, null);

    static ObservationRow obs(String value, String date) {
        return new ObservationRow(UUID.randomUUID(), UUID.randomUUID(), "S. Creatinine", value, "mg/dL", Instant.parse(date + "T00:00:00Z"), "crop-" + value);
    }

    @Test
    void threeRisingReadingsFireOnceAFourthDoesNotAReversalReArms() {
        List<ObservationRow> rows = new ArrayList<>(List.of(obs("1.1", "2026-03-01"), obs("1.3", "2026-06-01"), obs("1.5", "2026-09-01")));
        Optional<NudgeCandidate> third = rules.trendOf(chronic, "creatinine", rows, true);
        assertThat(third).isPresent();
        assertThat(third.get().vars().get("values")).isEqualTo("1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL");
        assertThat(third.get().numbers()).contains("1", "3", "5");
        assertThat(third.get().urgency()).isEqualTo(NudgeUrgency.THIS_WEEK);
        assertThat(third.get().cropKeys()).containsExactly("crop-1.1", "crop-1.3", "crop-1.5");
        // a fourth rising reading: same run start, same key → the ledger dedupes it
        rows.add(obs("1.7", "2026-09-15"));
        Optional<NudgeCandidate> fourth = rules.trendOf(chronic, "creatinine", rows, true);
        assertThat(fourth).isPresent();
        assertThat(fourth.get().triggerKey()).isEqualTo(third.get().triggerKey());
        // a falling reading then three more rising: a new run, a new key
        rows.add(obs("1.2", "2026-10-01"));
        assertThat(rules.trendOf(chronic, "creatinine", rows, true)).isEmpty();
        rows.add(obs("1.4", "2026-11-01"));
        rows.add(obs("1.6", "2026-12-01"));
        Optional<NudgeCandidate> again = rules.trendOf(chronic, "creatinine", rows, true);
        assertThat(again).isPresent();
        assertThat(again.get().triggerKey()).isNotEqualTo(third.get().triggerKey());
        assertThat(again.get().vars().get("values")).isEqualTo("1.2 mg/dL → 1.4 mg/dL → 1.6 mg/dL");
    }

    @Test
    void fallingIsNotWrongForARisingMarkerButEitherWayCountsForTsh() {
        List<ObservationRow> falling = List.of(obs("1.5", "2026-03-01"), obs("1.3", "2026-06-01"), obs("1.1", "2026-09-01"));
        assertThat(rules.trendOf(chronic, "creatinine", falling, true)).isEmpty();
        assertThat(rules.trendOf(chronic, "tsh", falling, false)).isPresent();
        List<ObservationRow> two = List.of(obs("1.1", "2026-03-01"), obs("1.3", "2026-06-01"));
        assertThat(rules.trendOf(chronic, "creatinine", two, true)).isEmpty();
        List<ObservationRow> bangla = List.of(obs("৭.১", "2026-03-01"), obs("৭.৬", "2026-06-01"), obs("৮.২", "2026-09-01"));
        assertThat(rules.trendOf(chronic, "hba1c", bangla, true)).isPresent();
    }

    @Test
    void silenceNeedsChronicFlagsAndSixtyQuietDaysAndIsSuppressedForThirty() {
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(NOW.minusSeconds(61L * 86400)));
        when(nudges.latest(P, NudgeRule.SILENCE)).thenReturn(Mono.empty());
        assertThat(rules.silence(noFlags).block()).isNull();           // no flags → never, whatever the quiet period
        NudgeCandidate c = rules.silence(chronic).block();
        assertThat(c).isNotNull();
        assertThat(c.vars().get("days")).isEqualTo("61");
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(NOW.minusSeconds(30L * 86400)));
        assertThat(rules.silence(chronic).block()).isNull();           // not quiet enough
        when(data.lastDocumentAt(P)).thenReturn(Mono.just(NOW.minusSeconds(61L * 86400)));
        when(nudges.latest(P, NudgeRule.SILENCE)).thenReturn(Mono.just(new Nudge(UUID.randomUUID(), F, P, NudgeRule.SILENCE, "silence:x",
                NudgeUrgency.ROUTINE, NudgeStatus.GATED, null, Map.of(), null, null, NOW.minusSeconds(10L * 86400), NOW)));
        assertThat(rules.silence(chronic).block()).isNull();           // suppressed for 30 days after the last one
    }

    static MedicationRow med(UUID doc, String date, String name, String dose, String freq) {
        return new MedicationRow(UUID.randomUUID(), P, F, doc, Instant.parse(date + "T00:00:00Z"), LocalDate.parse(date), name, dose, freq, null, null, "crop-" + name + date);
    }

    @Test
    void medicineChangedNamesWhatChangedVerbatimBothSidesAndNeedsAPriorPrescription() {
        UUID newer = UUID.randomUUID(), older = UUID.randomUUID();
        when(data.medicationsOfPatient(P)).thenReturn(Flux.just(
                med(newer, "2026-09-01", "Tab. Losartan", "50 mg", "0+0+1"), med(newer, "2026-09-01", "Tab. Metformin", "850 mg", "1+0+1"),
                med(older, "2026-06-01", "Tab. Losartan", "25 mg", "0+0+1"), med(older, "2026-06-01", "Tab. Metformin", "850 mg", "1+0+1"),
                med(older, "2026-06-01", "Tab. Amlodipine", "5 mg", "1+0+0")));
        NudgeCandidate c = rules.medicineChanged(P, newer).block();
        assertThat(c).isNotNull();
        assertThat(c.urgency()).isEqualTo(NudgeUrgency.THIS_WEEK);
        assertThat(c.vars().get("changes")).isEqualTo("Tab. Losartan — 25 mg · 0+0+1 → 50 mg · 0+0+1\nTab. Amlodipine — 5 mg · 1+0+0 → (নতুন প্রেসক্রিপশনে নেই)");
        assertThat(c.triggerKey()).isEqualTo("document:" + newer);
        assertThat(c.cropKeys()).hasSize(3);
        // the older prescription has no prior: nothing
        assertThat(rules.medicineChanged(P, older).block()).isNull();
    }

    @Test
    void courseEndingFiresTheDayBeforeTheWrittenCourseEndsAndSkipsUnparseableDurations() {
        UUID doc = UUID.randomUUID();
        // today 2026-09-19 Dhaka; lead 1 day → a course ending 2026-09-20: start 2026-09-14 for "৭ দিন" (14..20)
        MedicationRow seven = new MedicationRow(UUID.randomUUID(), P, F, doc, NOW.minusSeconds(5L * 86400), LocalDate.parse("2026-09-14"), "Cap. Omeprazole", "20 mg", "1+0+0", "before meal", "৭ দিন", "c1");
        MedicationRow ten = new MedicationRow(UUID.randomUUID(), P, F, doc, NOW.minusSeconds(5L * 86400), LocalDate.parse("2026-09-14"), "Tab. X", "1", "1+0+0", null, "10 days", "c2");
        MedicationRow vague = new MedicationRow(UUID.randomUUID(), P, F, doc, NOW.minusSeconds(5L * 86400), LocalDate.parse("2026-09-14"), "Tab. Y", "1", "1+0+0", null, "চলবে", "c3");
        when(data.medicationsWithDurationSince(any())).thenReturn(Flux.just(seven, ten, vague));
        List<NudgeCandidate> out = rules.courseEnding().collectList().block();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).vars()).containsEntry("medicine", "Cap. Omeprazole").containsEntry("end_date", "2026-09-20").containsEntry("duration_text", "৭ দিন");
        assertThat(out.get(0).triggerKey()).isEqualTo("medication:" + seven.id());
    }

    @Test
    void followUpDueLooksTwoDaysAheadAndCarriesTheInstructionVerbatim() {
        UUID id = UUID.randomUUID();
        when(data.openFollowUpsDueOn(eq(LocalDate.parse("2026-09-21")))).thenReturn(Flux.just(
                new NudgeDataPort.FollowUpRow(id, P, F, UUID.randomUUID(), "Follow up after 1 month", LocalDate.parse("2026-09-21"), "crop-f")));
        List<NudgeCandidate> out = rules.followUpDue().collectList().block();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).triggerKey()).isEqualTo("follow_up:" + id);
        assertThat(out.get(0).vars()).containsEntry("instruction", "Follow up after 1 month").containsEntry("due_date", "2026-09-21");
        assertThat(out.get(0).numbers()).contains("1", "2026", "09", "21");
    }

    /** PO ruling 2026-09-19: same-day readings are one report; three readings inside a fortnight are acute, not a trend. */
    @Test
    void sameDayReadingsCollapseAndAShortSpanIsNotATrend() {
        List<ObservationRow> sameDay = List.of(obs("1.1", "2026-09-01"), obs("1.3", "2026-09-01"), obs("1.5", "2026-09-01"));
        assertThat(rules.trendOf(chronic, "creatinine", sameDay, true)).isEmpty();
        List<ObservationRow> week = List.of(obs("1.1", "2026-09-01"), obs("1.3", "2026-09-04"), obs("1.5", "2026-09-07"));
        assertThat(rules.trendOf(chronic, "creatinine", week, true)).isEmpty();
        List<ObservationRow> fortnight = List.of(obs("1.1", "2026-09-01"), obs("1.3", "2026-09-08"), obs("1.5", "2026-09-15"));
        assertThat(rules.trendOf(chronic, "creatinine", fortnight, true)).isPresent();
        // a repeat on the last day does not make a fourth reading; the first of a day stands for the day
        List<ObservationRow> withRepeat = new ArrayList<>(fortnight);
        withRepeat.add(obs("1.9", "2026-09-15"));
        assertThat(rules.trendOf(chronic, "creatinine", withRepeat, true).get().vars().get("values")).isEqualTo("1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL");
    }
}
