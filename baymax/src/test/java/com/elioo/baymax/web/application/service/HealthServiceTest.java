package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.out.HealthReadPort;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan B6 step 1: the স্বাস্থ্য screen's series, blood-pressure pairs and the latest prescription, over a mocked read port.
 * Each join rule is tested in both directions, written together: what must become one series (or one pair) and what must
 * stay apart. The blood-pressure rows carry {@code source_line} in exactly the form {@code CropCutter} writes
 * ({@code "1:0-15"}) and two different crop keys, as production stores them; never a shared crop key.
 */
class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID MA = UUID.fromString("b6000000-0000-4000-8000-000000000002");

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final HealthReadPort reads = mock(HealthReadPort.class);
    private final HealthUseCase health = new HealthService(records, reads);
    private int ids;

    HealthServiceTest() {
        when(records.visiblePatient(any(), any())).thenReturn(Mono.empty());
        when(records.visiblePatient(FAMILY, MA)).thenReturn(Mono.just(new PatientAccess(
                new PatientProfile(MA, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), true)));
    }

    // ---- the series join, both directions ----------------------------------------------------------------------

    /** Review Focus 4: MICRO SIGN, GREEK MU and ASCII u are one unit; written as escapes so no editor can fold them. */
    @Test
    void microSignGreekMuAndAsciiUAreOneSeries() {
        String micro = "µmol/L";
        String mu = "μmol/L";
        assertThat(micro.codePointAt(0)).isEqualTo(0x00B5);
        assertThat(mu.codePointAt(0)).isEqualTo(0x03BC);
        UUID d1 = doc(), d2 = doc(), d3 = doc();
        HealthUseCase.HealthLabs labs = labs(
                row(d1, "2026-10-01", "Creatinine", "creatinine", "120", micro),
                row(d2, "2026-09-01", "S. Creatinine", "creatinine", "110", mu),
                row(d3, "2026-08-01", "Creatinine", "creatinine", "100", "umol/L"));
        assertThat(labs.series()).extracting(s -> s.points().size()).containsExactly(3);
        LabSeries creatinine = labs.series().get(0);
        assertThat(creatinine.points()).extracting(LabSeries.Point::value).containsExactly("120", "110", "100");
        assertThat(creatinine.name()).as("the newest point's name, as printed").isEqualTo("Creatinine");
        assertThat(creatinine.unit()).as("the newest point's unit, as printed").isEqualTo(micro);
    }

    /** The dangerous direction: an absolute count and a percentage of one cell type share a name and are two series. */
    @Test
    void aCountAndAPercentageOfOneCellTypeAreTwoSeries() {
        UUID d = doc();
        Map<String, Object> count = row(d, "2026-10-01", "Neutrophil", "neutrophil", "2.25", "10^3/µL");
        Map<String, Object> percent = row(d, "2026-10-01", "Neutrophil", "neutrophil", "41.9", "%");
        HealthUseCase.HealthLabs labs = labs(count, percent);
        assertThat(labs.series()).hasSize(2);
        for (LabSeries s : labs.series()) {
            assertThat(s.points()).hasSize(1);
            Map<String, Object> own = s.unit().equals("%") ? percent : count;
            assertThat(s.points().get(0).value()).as("the point of the " + s.unit() + " series is its own row's").isEqualTo(own.get("value"));
            assertThat(s.points().get(0).observationId()).isEqualTo(own.get("id"));
        }
    }

    @Test
    void theSameMarkerInTwoUnitsIsTwoSeries() {
        HealthUseCase.HealthLabs labs = labs(
                row(doc(), "2026-10-01", "Glucose", "fasting_glucose", "126", "mg/dL"),
                row(doc(), "2026-09-01", "Glucose", "fasting_glucose", "7.0", "mmol/L"));
        assertThat(labs.series()).hasSize(2);
        assertThat(labs.series()).extracting(LabSeries::unit).containsExactly("mg/dL", "mmol/L");
    }

    @Test
    void rowsAreNeverPairedByNameAlone() {
        UUID d1 = doc(), d2 = doc();
        List<Map<String, Object>> rows = List.of(
                row(d1, "2026-10-01", "HbA1c", "hba1c", "8.2", "%"),
                row(d1, "2026-10-01", "HbA1c", "hba1c", "66", "mmol/mol"),
                row(d2, "2026-07-01", "HbA1c", "hba1c", "7.9", "%"));
        HealthUseCase.HealthLabs labs = labs(rows.toArray(Map[]::new));
        assertThat(labs.series()).extracting(s -> s.points().size()).containsExactly(2, 1);
        List<Object> pointIds = new ArrayList<>();
        labs.series().forEach(s -> s.points().forEach(p -> pointIds.add(p.observationId())));
        assertThat(pointIds).as("no observation is in two points").doesNotHaveDuplicates();
        assertThat(pointIds).as("every observation is in a point").containsExactlyInAnyOrderElementsOf(rows.stream().map(r -> r.get("id")).toList());
    }

    // ---- blood pressure, both directions ---------------------------------------------------------------------

    @Test
    void aSystolicAndDiastolicOnOneLineAreOnePair() {
        UUID d = doc();
        HealthUseCase.HealthLabs labs = labs(
                bp(d, "bp_systolic", "130", "1:0-15", "crop-v1.jpg"),
                bp(d, "bp_diastolic", "80", "1:0-15", "crop-v2.jpg"));
        assertThat(labs.bp().points()).hasSize(1);
        LabSeries.BpPoint pair = labs.bp().points().get(0);
        assertThat(pair.systolic().value()).isEqualTo("130");
        assertThat(pair.diastolic().value()).isEqualTo("80");
        assertThat(pair.systolic().cropKey()).as("each half keeps its own crop").endsWith("crop-v1.jpg");
        assertThat(pair.diastolic().cropKey()).endsWith("crop-v2.jpg");
        assertThat(pair.documentId()).isEqualTo(d);
        assertThat(labs.series()).as("a paired reading is not also a single series").isEmpty();
    }

    @Test
    void twoPairsOnOnePaperPairByLineNotByOrder() {
        UUID d = doc();
        HealthUseCase.HealthLabs labs = labs(
                bp(d, "bp_systolic", "130", "1:0-15", "crop-v1.jpg"),
                bp(d, "bp_systolic", "150", "1:16-31", "crop-v2.jpg"),
                bp(d, "bp_diastolic", "95", "1:16-31", "crop-v3.jpg"),
                bp(d, "bp_diastolic", "85", "1:0-15", "crop-v4.jpg"));
        assertThat(labs.bp().points()).extracting(p -> p.systolic().value() + "/" + p.diastolic().value())
                .containsExactlyInAnyOrder("130/85", "150/95");
        assertThat(labs.series()).isEmpty();
    }

    @Test
    void aSystolicWithoutAPartnerStaysAlone() {
        UUID d = doc();
        HealthUseCase.HealthLabs labs = labs(
                bp(d, "bp_systolic", "130", "1:0-15", "crop-v1.jpg"),
                bp(d, "bp_diastolic", "80", "1:16-31", "crop-v2.jpg"));
        assertThat(labs.bp().points()).isEmpty();
        assertThat(labs.series()).extracting(LabSeries::canonicalName).containsExactlyInAnyOrder("bp_systolic", "bp_diastolic");
        assertThat(labs.series()).allSatisfy(s -> assertThat(s.points()).hasSize(1));
    }

    /** A paper read before B6 has no line: its readings stay two single series until it is re-cropped (Q8). */
    @Test
    void aReadingWithNoLineIsNeverPaired() {
        UUID d = doc();
        HealthUseCase.HealthLabs labs = labs(
                bp(d, "bp_systolic", "130", null, "crop-v1.jpg"),
                bp(d, "bp_diastolic", "80", null, "crop-v2.jpg"));
        assertThat(labs.bp().points()).isEmpty();
        assertThat(labs.series()).extracting(LabSeries::canonicalName).containsExactlyInAnyOrder("bp_systolic", "bp_diastolic");
    }

    @Test
    void twoSystolicsOnOneLineAreNotAPair() {
        UUID d = doc();
        HealthUseCase.HealthLabs labs = labs(
                bp(d, "bp_systolic", "130", "1:0-15", "crop-v1.jpg"),
                bp(d, "bp_systolic", "135", "1:0-15", "crop-v2.jpg"),
                bp(d, "bp_diastolic", "80", "1:0-15", "crop-v3.jpg"));
        assertThat(labs.bp().points()).isEmpty();
        assertThat(labs.series()).extracting(s -> s.canonicalName() + ":" + s.points().size())
                .containsExactlyInAnyOrder("bp_systolic:2", "bp_diastolic:1");
    }

    // ---- display ---------------------------------------------------------------------------------------------

    @Test
    void aOneSidedInsideIsNeverInside() {
        Map<String, Object> r = row(doc(), "2026-10-01", "Cholesterol", "cholesterol", "180", "mg/dL");
        r.remove("ref_low");
        r.put("ref_high", "200");
        r.put("verdict", "inside");
        assertThat(onlyPoint(labs(r)).kind()).isEqualTo(ValueVerdict.Kind.UNKNOWN);
    }

    @Test
    void aTwoSidedInsideProvedByCodeStaysInside() {
        Map<String, Object> r = row(doc(), "2026-10-01", "Glucose", "fasting_glucose", "95", "mg/dL");
        r.put("ref_low", "70");
        r.put("ref_high", "110");
        r.put("verdict", "inside");
        LabSeries.Point p = onlyPoint(labs(r));
        assertThat(p.kind()).isEqualTo(ValueVerdict.Kind.INSIDE);
        assertThat(p.side()).isNull();
    }

    @Test
    void aSidelessOutsideIsUnknown() {
        Map<String, Object> r = row(doc(), "2026-10-01", "Potassium", "potassium", "6.4", "mmol/L");
        r.put("verdict", "outside");
        assertThat(onlyPoint(labs(r)).kind()).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(onlyPoint(labs(r)).side()).isNull();
    }

    @Test
    void anOutsideWithItsSideKeepsIt() {
        Map<String, Object> r = row(doc(), "2026-10-01", "Potassium", "potassium", "6.4", "mmol/L");
        r.put("verdict", "outside");
        r.put("verdict_side", "high");
        LabSeries.Point p = onlyPoint(labs(r));
        assertThat(p.kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
        assertThat(p.side()).isEqualTo("high");
    }

    @Test
    void seriesAreOrderedByTheirNewestPointNewestFirst() {
        UUID older = doc(), newer = doc();
        HealthUseCase.HealthLabs labs = labs(
                row(newer, "2026-10-01", "TSH", "tsh", "2.1", "mIU/L"),
                row(newer, "2026-10-01", "HbA1c", "hba1c", "8.2", "%"),
                row(older, "2026-01-01", "LDL", "ldl", "130", "mg/dL"),
                row(older, "2026-01-01", "TSH", "tsh", "2.4", "mIU/L"));
        assertThat(labs.series()).extracting(LabSeries::canonicalName).containsExactly("tsh", "hba1c", "ldl");
        assertThat(labs.series().get(0).points()).extracting(LabSeries.Point::date)
                .containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-01-01"));
    }

    @Test
    void theFilingDayAndItsMarksAreThePortsOwn() {
        Map<String, Object> r = row(doc(), "2026-10-01", "TSH", "tsh", "2.1", "mIU/L");
        r.put("date_is_fallback", true);
        r.put("date_uncertain", false);
        Map<String, Object> marked = row(doc(), "2026-09-01", "TSH", "tsh", "2.4", "mIU/L");
        marked.put("date_uncertain", true);
        List<LabSeries.Point> points = labs(r, marked).series().get(0).points();
        assertThat(points).extracting(LabSeries.Point::dateIsUploadDay).containsExactly(true, false);
        assertThat(points).extracting(LabSeries.Point::dateUncertain).containsExactly(false, true);
    }

    @Test
    void anotherFamilysPatientIsNotFound() {
        when(reads.labPoints(MA)).thenReturn(Flux.just(row(doc(), "2026-10-01", "TSH", "tsh", "2.1", "mIU/L")));
        assertThatThrownBy(() -> health.labs(UUID.randomUUID(), MA).block())
                .isInstanceOfSatisfying(BaymaxException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> health.medicines(UUID.randomUUID(), MA).block())
                .isInstanceOfSatisfying(BaymaxException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    // ---- medicines -----------------------------------------------------------------------------------------

    @Test
    void onlyTheLatestPrescriptionsMedicines() {
        UUID latest = doc();
        UUID newer = doc();
        LatestPrescription served = new LatestPrescription(latest, LocalDate.parse("2026-10-01"), false, true,
                List.of(medicine("Tab. Demoprol", "50 mg"), medicine("Tab. Fictazide", "25 mg")), Optional.of(newer));
        when(reads.latestPrescription(MA)).thenReturn(Mono.just(served));
        LatestPrescription got = health.medicines(FAMILY, MA).block();
        assertThat(got.documentId()).isEqualTo(latest);
        assertThat(got.date()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(got.dateIsUploadDay()).isFalse();
        assertThat(got.dateUncertain()).isTrue();
        assertThat(got.newerNotRead()).contains(newer);
        assertThat(got.medicines()).isEqualTo(served.medicines());
    }

    @Test
    void noFieldOutsideMedicineFields() {
        Map<String, Object> row = medicine("Tab. Demoprol", "50 mg");
        row.put("action", "stopped");
        row.put("purpose", "BP");
        when(reads.latestPrescription(MA)).thenReturn(Mono.just(new LatestPrescription(doc(), LocalDate.parse("2026-10-01"), false, false,
                List.of(row), Optional.empty())));
        Map<String, Object> got = health.medicines(FAMILY, MA).block().medicines().get(0);
        assertThat(got).doesNotContainKeys("action", "purpose");
        Set<String> allowed = new java.util.HashSet<>(com.elioo.baymax.web.domain.PaperDisplay.MEDICINE_FIELDS);
        allowed.add("crop_key");
        assertThat(allowed).containsAll(got.keySet());
        assertThat(got).containsEntry("name", "Tab. Demoprol").containsEntry("dose_text", "50 mg").containsKey("crop_key");
    }

    @Test
    void noPrescriptionYetIsSaidWithTheNewerOneStillBeingRead() {
        UUID reading = doc();
        when(reads.latestPrescription(MA)).thenReturn(Mono.just(LatestPrescription.none(Optional.of(reading))));
        LatestPrescription got = health.medicines(FAMILY, MA).block();
        assertThat(got.found()).isFalse();
        assertThat(got.medicines()).isEmpty();
        assertThat(got.newerNotRead()).contains(reading);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private HealthUseCase.HealthLabs labs(Map<?, ?>... rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<?, ?> r : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) r;
            list.add(typed);
        }
        when(reads.labPoints(MA)).thenReturn(Flux.fromIterable(list));
        return health.labs(FAMILY, MA).block();
    }

    private static LabSeries.Point onlyPoint(HealthUseCase.HealthLabs labs) {
        assertThat(labs.series()).hasSize(1);
        assertThat(labs.series().get(0).points()).hasSize(1);
        return labs.series().get(0).points().get(0);
    }

    private UUID doc() {
        return UUID.fromString(String.format("b6000000-0000-4000-8000-1%011d", ++ids));
    }

    /** One observation as the read port gives it: the keys PaperDisplay.kindOf and PrintedRange read, plus the join's. */
    private Map<String, Object> row(UUID document, String date, String name, String canonical, String value, String unit) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", UUID.fromString(String.format("b6000000-0000-4000-8000-2%011d", ++ids)));
        m.put("document_id", document);
        m.put("name", name);
        m.put("canonical_name", canonical);
        m.put("value", value);
        if (unit != null) {
            m.put("unit", unit);
        }
        m.put("crop_key", "f/p/" + document + "/crop-v" + ids + ".jpg");
        m.put("date", LocalDate.parse(date));
        m.put("date_is_fallback", false);
        m.put("date_uncertain", false);
        return m;
    }

    private Map<String, Object> bp(UUID document, String canonical, String value, String line, String crop) {
        Map<String, Object> m = row(document, "2026-10-01", "BP", canonical, value, "mmHg");
        m.put("crop_key", "f/p/" + document + "/" + crop);
        if (line != null) {
            m.put("source_line", line);
        }
        return m;
    }

    private Map<String, Object> medicine(String name, String dose) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dose_text", dose);
        m.put("crop_key", "f/p/d/crop-m" + (++ids) + ".jpg");
        return m;
    }
}
