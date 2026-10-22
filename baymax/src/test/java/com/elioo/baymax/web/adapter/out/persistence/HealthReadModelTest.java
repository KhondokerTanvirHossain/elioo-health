package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.web.application.port.out.HealthReadPort;
import com.elioo.baymax.web.domain.LatestPrescription;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6 step 4: the স্বাস্থ্য read model on real Postgres. The lab points are this patient's values on DONE papers, in
 * the timeline's own order; the latest prescription is the newest DONE prescription by filing day, whatever its medicine
 * count — never an older list presented as the latest (Q3, decision 9) — and a newer paper not read yet is said.
 */
@Testcontainers
class HealthReadModelTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static UUID patient(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000008" + String.format("%02d", FAMILIES.incrementAndGet()),
                "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)).block().id();
    }

    /** A paper with exactly these columns: created through the port, then set in SQL so created_at and the printed date are exact. */
    private static UUID doc(ApplicationContext context, UUID patient, String createdAt, String printed, String type, String status) throws Exception {
        UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.patient_profile WHERE id = '" + patient + "'"));
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(patient, family, 1, NOW)).block().id();
        sql("UPDATE baymax.document SET status = '" + status + "', document_type = " + (type == null ? "NULL" : "'" + type + "'")
                + ", created_at = '" + createdAt + "', doc_date = " + (printed == null ? "NULL" : "'" + printed + "'")
                + (status.equals("NEEDS_RETAKE") ? ", status_reason_code = 'low_overall', status_reason = 'developer prose'" : "")
                + " WHERE id = '" + id + "'");
        return id;
    }

    private static void value(UUID doc, String name, String canonical, String value, String unit) throws Exception {
        sql("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) "
                + "SELECT patient_id, id, '" + name + "', " + (canonical == null ? "NULL" : "'" + canonical + "'") + ", '" + value + "', '"
                + unit + "', 'k/" + doc + "/crop-v" + name.length() + value + ".jpg', now() FROM baymax.document WHERE id = '" + doc + "'");
    }

    private static void medicine(UUID doc, String name) throws Exception {
        sql("INSERT INTO baymax.medication_event (patient_id, document_id, name, dose_text, action, crop_key, at) "
                + "SELECT patient_id, id, '" + name + "', '50 mg', 'stopped', 'k/" + doc + "/crop-m-" + name + ".jpg', now() "
                + "FROM baymax.document WHERE id = '" + doc + "'");
    }

    @Test
    void labPointsAreThisPatientsDonePapersNewestFirst() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID sibling = patient(context);
            UUID older = doc(context, ma, "2026-10-01T08:00:00Z", "2026-03-01", "lab_report", "DONE");
            UUID newer = doc(context, ma, "2026-09-01T08:00:00Z", "2026-09-01", "lab_report", "DONE");
            UUID reading = doc(context, ma, "2026-10-10T08:00:00Z", null, "lab_report", "PROCESSING");
            UUID retake = doc(context, ma, "2026-10-11T08:00:00Z", "2026-10-11", "lab_report", "NEEDS_RETAKE");
            UUID theirs = doc(context, sibling, "2026-10-02T08:00:00Z", "2026-10-02", "lab_report", "DONE");
            value(older, "Creatinine", "creatinine", "100", "umol/L");
            value(newer, "Creatinine", "creatinine", "120", "umol/L");
            value(reading, "Creatinine", "creatinine", "999", "umol/L");
            value(retake, "Creatinine", "creatinine", "998", "umol/L");
            value(theirs, "Creatinine", "creatinine", "997", "umol/L");
            sql("UPDATE baymax.observation SET source_line = '1:0-15' WHERE document_id = '" + newer + "'");

            List<Map<String, Object>> points = context.getBean(HealthReadPort.class).labPoints(ma).collectList().block();
            assertThat(points).extracting(p -> p.get("value")).containsExactly("120", "100");
            assertThat(points).extracting(p -> p.get("document_id")).containsExactly(newer, older);
            Map<String, Object> first = points.get(0);
            assertThat(first).containsEntry("canonical_name", "creatinine").containsEntry("unit", "umol/L")
                    .containsEntry("date", LocalDate.parse("2026-09-01")).containsEntry("date_is_fallback", false)
                    .containsEntry("date_uncertain", false).containsEntry("source_line", "1:0-15");
            assertThat(first.get("id")).isInstanceOf(UUID.class);
            assertThat((String) first.get("crop_key")).contains(newer.toString());
            assertThat(points.get(1)).doesNotContainKey("source_line");
        });
    }

    @Test
    void theFilingDayAndItsUncertainMarkAreRead() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID undated = doc(context, ma, "2026-10-01T20:00:00Z", null, "lab_report", "DONE");   // Dhaka: 2 October
            UUID marked = doc(context, ma, "2026-09-01T08:00:00Z", "2026-08-30", "lab_report", "DONE");
            sql("UPDATE baymax.document SET doc_date_uncertain = true WHERE id = '" + marked + "'");
            value(undated, "TSH", "tsh", "2.1", "mIU/L");
            value(marked, "TSH", "tsh", "2.4", "mIU/L");
            List<Map<String, Object>> points = context.getBean(HealthReadPort.class).labPoints(ma).collectList().block();
            assertThat(points).extracting(p -> p.get("date"))
                    .containsExactly(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-08-30"));
            assertThat(points).extracting(p -> p.get("date_is_fallback")).containsExactly(true, false);
            assertThat(points).extracting(p -> p.get("date_uncertain")).containsExactly(false, true);
        });
    }

    /**
     * PO ruling R3 (DR-48) on real Postgres: the range a point's band is drawn from is the stored extraction's printed
     * range ({@code observation.ref_low}, {@code ref_high}) as stored, one side or both, through the real labs use case,
     * kept only where it agrees with the stored verdict. A value whose paper printed none carries none: urea is flagged
     * OUTSIDE high with no printed range (a threshold's verdict), so a default range leaked onto it would agree with its
     * chip and be drawn, and this check would see it.
     */
    @Test
    void eachPointCarriesItsStoredPrintedRange() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.patient_profile WHERE id = '" + ma + "'"));
            UUID d = doc(context, ma, "2026-10-01T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            value(d, "Creatinine", "creatinine", "132", "umol/L");
            value(d, "Total Cholesterol", "total_cholesterol", "240", "mg/dL");
            value(d, "Urea", "urea", "150", "mg/dL");
            value(d, "HDL Cholesterol", "hdl", "45", "mg/dL");
            sql("UPDATE baymax.observation SET ref_low = '59', ref_high = '104' WHERE canonical_name = 'creatinine' AND document_id = '" + d + "'");
            sql("UPDATE baymax.observation SET ref_high = '<200' WHERE canonical_name = 'total_cholesterol' AND document_id = '" + d + "'");
            sql("UPDATE baymax.observation SET ref_low = '>40' WHERE canonical_name = 'hdl' AND document_id = '" + d + "'");
            for (String outsideHigh : List.of("creatinine", "total_cholesterol", "urea")) {
                sql("UPDATE baymax.observation SET verdict = 'outside', verdict_level = 'this_week', verdict_side = 'high',"
                        + " verdict_reason = 'value_outside_range:" + outsideHigh + "|threshold:test' WHERE canonical_name = '"
                        + outsideHigh + "' AND document_id = '" + d + "'");
            }

            List<Map<String, Object>> points = context.getBean(HealthReadPort.class).labPoints(ma).collectList().block();
            Map<Object, Map<String, Object>> byName = new java.util.HashMap<>();
            points.forEach(p -> byName.put(p.get("canonical_name"), p));
            assertThat(byName.get("creatinine")).containsEntry("ref_low", "59").containsEntry("ref_high", "104");
            assertThat(byName.get("total_cholesterol")).doesNotContainKey("ref_low").containsEntry("ref_high", "<200");
            assertThat(byName.get("urea")).doesNotContainKeys("ref_low", "ref_high");

            var labs = context.getBean(com.elioo.baymax.web.application.port.in.HealthUseCase.class).labs(family, ma).block();
            Map<String, com.elioo.baymax.web.domain.PrintedBand> band = new java.util.HashMap<>();
            labs.series().forEach(s -> band.put(s.canonicalName(), s.points().get(0).range()));
            assertThat(band.get("creatinine")).isEqualTo(new com.elioo.baymax.web.domain.PrintedBand(59.0, 104.0, "59–104"));
            assertThat(band.get("total_cholesterol")).isEqualTo(new com.elioo.baymax.web.domain.PrintedBand(null, 200.0, "<200"));
            assertThat(band.get("hdl")).as("a NULL verdict beside a one-sided bound: the words, no number")
                    .isEqualTo(new com.elioo.baymax.web.domain.PrintedBand(null, null, ">40"));
            assertThat(band.get("urea")).as("no printed range: none, never a default").isEqualTo(com.elioo.baymax.web.domain.PrintedBand.NONE);
        });
    }

    @Test
    void aRowWithoutACanonicalNameIsNoPoint() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID d = doc(context, ma, "2026-10-01T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            value(d, "Mystery", null, "5", "x");
            value(d, "TSH", "tsh", "2.1", "mIU/L");
            assertThat(context.getBean(HealthReadPort.class).labPoints(ma).collectList().block())
                    .extracting(p -> p.get("name")).containsExactly("TSH");
        });
    }

    @Test
    void theLatestPrescriptionIsByTimelineDateThenUpload() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID newerDated = doc(context, ma, "2026-09-01T08:00:00Z", "2026-08-20", "prescription", "DONE");
            UUID olderDatedUploadedLater = doc(context, ma, "2026-10-01T08:00:00Z", "2026-05-01", "prescription", "DONE");
            medicine(newerDated, "Tab. Demoprol");
            medicine(olderDatedUploadedLater, "Tab. Fictazide");
            LatestPrescription latest = context.getBean(HealthReadPort.class).latestPrescription(ma).block();
            assertThat(latest.documentId()).isEqualTo(newerDated);
            assertThat(latest.date()).isEqualTo(LocalDate.parse("2026-08-20"));
            assertThat(latest.medicines()).extracting(m -> m.get("name")).containsExactly("Tab. Demoprol");
            assertThat(latest.newerNotRead()).isEmpty();

            // the same filing day: the later upload wins
            UUID sameDayLater = doc(context, ma, "2026-09-02T08:00:00Z", "2026-08-20", "prescription", "DONE");
            assertThat(context.getBean(HealthReadPort.class).latestPrescription(ma).block().documentId()).isEqualTo(sameDayLater);
        });
    }

    /** Decision 9's dangerous direction: an empty newest list is the answer, never the older list. */
    @Test
    void aNewerPrescriptionWithNoMedicinesIsTheLatest() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID older = doc(context, ma, "2026-08-01T08:00:00Z", "2026-08-01", "prescription", "DONE");
            medicine(older, "Tab. Demoprol");
            medicine(older, "Tab. Fictazide");
            UUID newer = doc(context, ma, "2026-10-01T08:00:00Z", "2026-10-01", "prescription", "DONE");
            LatestPrescription latest = context.getBean(HealthReadPort.class).latestPrescription(ma).block();
            assertThat(latest.medicines()).as("the newest prescription's list, empty; never the older paper's").isEmpty();
            assertThat(latest.documentId()).as("the newest prescription, not the older one with medicines").isEqualTo(newer);
            assertThat(latest.date()).isEqualTo(LocalDate.parse("2026-10-01"));
        });
    }

    @Test
    void aNewerPrescriptionStillBeingReadIsSaid() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID done = doc(context, ma, "2026-08-01T08:00:00Z", "2026-08-01", "prescription", "DONE");
            medicine(done, "Tab. Demoprol");
            HealthReadPort port = context.getBean(HealthReadPort.class);

            UUID labAfter = doc(context, ma, "2026-08-05T08:00:00Z", "2026-08-05", "lab_report", "DONE");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("a later DONE lab report sets nothing").isEmpty();
            UUID labRetake = doc(context, ma, "2026-08-06T08:00:00Z", null, "lab_report", "NEEDS_RETAKE");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("a lab report's retake is not a prescription").isEmpty();

            UUID retake = doc(context, ma, "2026-08-10T08:00:00Z", null, "prescription", "NEEDS_RETAKE");
            LatestPrescription withRetake = port.latestPrescription(ma).block();
            assertThat(withRetake.documentId()).isEqualTo(done);
            assertThat(withRetake.medicines()).extracting(m -> m.get("name")).containsExactly("Tab. Demoprol");
            assertThat(withRetake.newerNotRead()).contains(retake);

            UUID untyped = doc(context, ma, "2026-08-12T08:00:00Z", null, null, "NEEDS_RETAKE");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("a retake of unknown type").contains(untyped);

            UUID processing = doc(context, ma, "2026-08-15T08:00:00Z", null, null, "PROCESSING");
            LatestPrescription withProcessing = port.latestPrescription(ma).block();
            assertThat(withProcessing.documentId()).isEqualTo(done);
            assertThat(withProcessing.newerNotRead()).contains(processing);

            UUID before = doc(context, ma, "2026-07-01T08:00:00Z", null, null, "RECEIVED");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("only a paper uploaded after it").contains(processing);
            assertThat(List.of(labAfter, labRetake, before)).doesNotContain(port.latestPrescription(ma).block().newerNotRead().orElseThrow());
        });
    }

    /**
     * Review of #122, finding 4: a retake that has since been replaced by a paper that was read is not owed any more. The
     * chat stack (IN_FLIGHT) already drops it; the health screen must agree. Both directions: a replacement that was read
     * (here a lab report) silences it, a replacement that FAILED does not.
     */
    @Test
    void aReplacedRetakeIsNotSaid() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID done = doc(context, ma, "2026-08-01T08:00:00Z", "2026-08-01", "prescription", "DONE");
            UUID retake = doc(context, ma, "2026-08-10T08:00:00Z", null, "prescription", "NEEDS_RETAKE");
            UUID replacement = doc(context, ma, "2026-08-11T08:00:00Z", "2026-08-11", "lab_report", "DONE");
            replaces(replacement, retake);
            HealthReadPort port = context.getBean(HealthReadPort.class);
            LatestPrescription latest = port.latestPrescription(ma).block();
            assertThat(latest.documentId()).isEqualTo(done);
            assertThat(latest.newerNotRead()).as("the retake was replaced by a paper that was read").isEmpty();

            sql("UPDATE baymax.document SET status = 'FAILED' WHERE id = '" + replacement + "'");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("a FAILED replacement leaves the retake owed").contains(retake);
        });
    }

    /**
     * Review of #122, finding 8: P is read; a later prescription N waits for a retake; its retake R is read with an older
     * printed date than P, so P stays the latest. N was retaken and read: it is not "a newer prescription not read yet".
     */
    @Test
    void aRetakenPrescriptionIsNotSaidWhenItsRetakeWasRead() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID p = doc(context, ma, "2026-08-01T08:00:00Z", "2026-08-01", "prescription", "DONE");
            UUID n = doc(context, ma, "2026-08-10T08:00:00Z", null, "prescription", "NEEDS_RETAKE");
            UUID r = doc(context, ma, "2026-08-11T08:00:00Z", "2026-07-01", "prescription", "DONE");
            replaces(r, n);
            LatestPrescription latest = context.getBean(HealthReadPort.class).latestPrescription(ma).block();
            assertThat(latest.documentId()).as("the retake's printed date is older, so P stays the latest").isEqualTo(p);
            assertThat(latest.newerNotRead()).as("N was retaken and read").isEmpty();
        });
    }

    /**
     * Review of #122, finding 7 (widens the plan's definition; Pending for the PO beside Q8): a later upload that FAILED on
     * our side was never read, and the family still owes a readable photo, so the older list is not the latest unsaid. A
     * FAILED paper typed as something else is not a prescription; one replaced by a retake that is not FAILED is not owed.
     */
    @Test
    void aLaterFailedUploadIsSaid() {
        runner().run(context -> {
            UUID ma = patient(context);
            doc(context, ma, "2026-08-01T08:00:00Z", "2026-08-01", "prescription", "DONE");
            HealthReadPort port = context.getBean(HealthReadPort.class);
            doc(context, ma, "2026-08-05T08:00:00Z", null, "lab_report", "FAILED");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("a FAILED lab report is not a prescription").isEmpty();

            UUID failed = doc(context, ma, "2026-08-10T08:00:00Z", null, null, "FAILED");
            assertThat(port.latestPrescription(ma).block().newerNotRead()).contains(failed);

            UUID retake = doc(context, ma, "2026-08-12T08:00:00Z", "2026-07-01", "prescription", "DONE");
            replaces(retake, failed);
            assertThat(port.latestPrescription(ma).block().newerNotRead()).as("the FAILED paper was retaken and read").isEmpty();
        });
    }

    @Test
    void aLabReportsMedicinesAreNotAPrescription() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID lab = doc(context, ma, "2026-10-01T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            medicine(lab, "Tab. Demoprol");
            LatestPrescription latest = context.getBean(HealthReadPort.class).latestPrescription(ma).block();
            assertThat(latest.found()).isFalse();
            assertThat(latest.documentId()).isNull();
            assertThat(latest.medicines()).isEmpty();
        });
    }

    @Test
    void noDonePrescriptionStillSaysOneIsBeingRead() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID reading = doc(context, ma, "2026-10-01T08:00:00Z", null, null, "RECEIVED");
            LatestPrescription latest = context.getBean(HealthReadPort.class).latestPrescription(ma).block();
            assertThat(latest.found()).isFalse();
            assertThat(latest.newerNotRead()).contains(reading);
        });
    }

    @Test
    void theMedicineRowsCarryTheirWrittenFieldsAndCrop() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID rx = doc(context, ma, "2026-10-01T08:00:00Z", "2026-10-01", "prescription", "DONE");
            medicine(rx, "Tab. Demoprol");
            Map<String, Object> row = context.getBean(HealthReadPort.class).latestPrescription(ma).block().medicines().get(0);
            assertThat(row).containsEntry("name", "Tab. Demoprol").containsEntry("dose_text", "50 mg");
            assertThat((String) row.get("crop_key")).contains(rx.toString());
            assertThat(row).doesNotContainKey("action");
        });
    }

    /** D1: {@code retake} is the paper sent in place of {@code replaced}. */
    private static void replaces(UUID retake, UUID replaced) throws Exception {
        sql("UPDATE baymax.document SET replaces_document_id = '" + replaced + "' WHERE id = '" + retake + "'");
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
