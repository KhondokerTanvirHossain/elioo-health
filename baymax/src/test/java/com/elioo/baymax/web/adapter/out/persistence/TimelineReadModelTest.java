package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.TimelineCursor;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timeline read model on real Postgres (C2): order, cursor, filter, stored urgency, verdict counts, review state,
 * retake code and attention. Every row comes from SQL over stored columns; nothing here assesses. Each test makes its
 * own family and patient, so the tests run in any order against the one container.
 */
@Testcontainers
class TimelineReadModelTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    /** A fresh family (fictional number in the fixture range) with one patient, Ma. */
    private static UUID patient(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000009" + String.format("%02d", FAMILIES.incrementAndGet()),
                "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)).block().id();
    }

    /** A document with exactly these columns (created through the port, then set in SQL so created_at and doc_date are exact). */
    private static UUID doc(ApplicationContext context, UUID patient, String createdAt, String docDate, String type, String status) throws Exception {
        UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.patient_profile WHERE id = '" + patient + "'"));
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(patient, family, 1, NOW)).block().id();
        sql("UPDATE baymax.document SET status = '" + status + "', document_type = " + (type == null ? "NULL" : "'" + type + "'")
                + ", created_at = '" + createdAt + "', doc_date = " + (docDate == null ? "NULL" : "'" + docDate + "'")
                + (status.equals("NEEDS_RETAKE") ? ", status_reason_code = 'low_overall', status_reason = 'developer prose'" : "")
                + " WHERE id = '" + id + "'");
        return id;
    }

    private static void urgency(UUID doc, String level) throws Exception {
        sql("UPDATE baymax.document SET urgency = '" + level + "', urgency_reasons = '[]', urgency_assessed_at = now() WHERE id = '" + doc + "'");
    }

    /** One value row; {@code verdict} null is a value not yet assessed (V19 allows it; the sweep fills it later). */
    private static void observation(UUID doc, String verdict) throws Exception {
        String v = verdict == null ? "NULL, NULL, NULL, NULL"
                : "'" + verdict + "', '" + (verdict.equals("critical") ? "now" : verdict.equals("outside") ? "this_week" : "routine") + "', "
                + (verdict.equals("critical") || verdict.equals("outside") ? "'high'" : "NULL") + ", "
                + "'value_" + (verdict.equals("critical") ? "critical" : verdict.equals("outside") ? "outside_range" : verdict) + ":x|stopgap'";
        sql("INSERT INTO baymax.observation (patient_id, document_id, name, value, crop_key, observed_at, verdict, verdict_level, verdict_side, verdict_reason) "
                + "SELECT patient_id, id, 'X', '1', 'k/c.jpg', now(), " + v + " FROM baymax.document WHERE id = '" + doc + "'");
    }

    private static void explanation(UUID doc, String gate, String createdAt) throws Exception {
        sql("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status, created_at) "
                + "SELECT family_id, id, 'explanation', 'routine', '[]', '" + gate + "', '" + createdAt + "' FROM baymax.document WHERE id = '" + doc + "'");
    }

    private static void explanation(UUID doc, String gate) throws Exception {
        explanation(doc, gate, NOW.toString());
    }

    /** The defect a created_at-only cursor has: 21 rows sharing one instant and one date come back exactly once each. */
    @Test
    void rowsSharingADateAndAnInstantAreNeitherSkippedNorRepeated() {
        runner().run(context -> {
            UUID p = patient(context);
            for (int i = 0; i < 21; i++) {
                doc(context, p, "2026-10-14T08:00:00Z", "2026-10-12", "lab_report", "DONE");
            }
            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            List<TimelineEntry> first = port.page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 21).collectList().block();
            TimelineEntry last = first.get(19);
            List<TimelineEntry> second = port.page(p, new TimelineCursor(last.date(), last.createdAt(), last.documentId()),
                    TimelineFilter.ALL, 21).collectList().block();
            assertThat(first).hasSize(21);
            assertThat(second).hasSize(1);
            assertThat(Stream.concat(first.subList(0, 20).stream(), second.stream()).map(TimelineEntry::documentId).distinct()).hasSize(21);
        });
    }

    /** §4.15: newest DOCUMENT date first. A 12 July report uploaded today sits below a 12 October one uploaded last week. */
    @Test
    void theTimelineIsOrderedByDocumentDateNotByUpload() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID october = doc(context, p, "2026-10-13T08:00:00Z", "2026-10-12", "lab_report", "DONE");
            UUID july = doc(context, p, "2026-10-20T03:00:00Z", "2026-07-12", "lab_report", "DONE");
            assertThat(context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(october, july);
        });
    }

    /** An undated upload falls back to its upload day in Dhaka (F7) and says so. */
    @Test
    void anUndatedUploadUsesItsDhakaUploadDay() {
        runner().run(context -> {
            UUID p = patient(context);
            doc(context, p, "2026-10-18T19:30:00Z", null, "prescription", "DONE");   // 01:30 on 19 October in Dhaka
            TimelineEntry e = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).blockFirst();
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-10-19"));
            assertThat(e.dateIsFallback()).isTrue();
        });
    }

    @Test
    void anEntryCarriesItsStoredUrgencyOutsideCountAndReviewState() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID d = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(d, "this_week");
            observation(d, "outside");
            observation(d, "critical");
            observation(d, "inside");
            observation(d, null);   // not yet assessed: counted as a value, as neither inside nor outside
            explanation(d, "released", "2026-10-19T08:01:00Z");
            explanation(d, "pending", "2026-10-19T08:02:00Z");   // the latest explanation is the review state
            TimelineEntry e = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).blockFirst();
            assertThat(e.urgency()).isEqualTo(Urgency.THIS_WEEK);
            assertThat(e.values()).isEqualTo(4);
            assertThat(e.outside()).isEqualTo(2);
            assertThat(e.reviewState()).isEqualTo("pending");
            assertThat(e.chip()).contains(Urgency.THIS_WEEK);
        });
    }

    /**
     * The columns the old per-row path filled, pinned on the read model: the unverified sum is
     * VerifiedItems.Unverified.total()'s (all four sections), page one's key is StorageKeys', the type and facility are
     * as stored, and a paper with no explanation has no review state.
     */
    @Test
    void anEntryCarriesTheUnverifiedTotalPageKeyAndSectionCounts() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID d = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "prescription", "DONE");
            sql("UPDATE baymax.document SET facility = 'Shapla Diagnostics', unverified_values = 1, unverified_medicines = 2, "
                    + "unverified_follow_up = 3, unverified_clinical_context = 4 WHERE id = '" + d + "'");
            for (int i = 0; i < 2; i++) {
                sql("INSERT INTO baymax.medication_event (patient_id, document_id, name, crop_key, at) "
                        + "SELECT patient_id, id, 'M', 'k/m.jpg', now() FROM baymax.document WHERE id = '" + d + "'");
            }
            sql("INSERT INTO baymax.follow_up (patient_id, document_id, instruction, crop_key) "
                    + "SELECT patient_id, id, 'F', 'k/f.jpg' FROM baymax.document WHERE id = '" + d + "'");
            UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.document WHERE id = '" + d + "'"));
            TimelineEntry e = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).blockFirst();
            assertThat(e.unverified()).isEqualTo(new com.elioo.baymax.extraction.domain.VerifiedItems.Unverified(1, 2, 3, 4).total());
            assertThat(e.pageOneKey()).isEqualTo(StorageKeys.page(family, p, d, 1));
            assertThat(e.medicines()).isEqualTo(2);
            assertThat(e.values()).isZero();
            assertThat(e.followUps()).isEqualTo(1);
            assertThat(e.documentType()).isEqualTo("prescription");
            assertThat(e.facility()).isEqualTo("Shapla Diagnostics");
            assertThat(e.status()).isEqualTo("DONE");
            assertThat(e.reviewState()).isNull();
            assertThat(e.dateIsFallback()).isFalse();
        });
    }

    /** F5: a retake row carries its machine code, which a page maps to Bangla; a finished row carries none. */
    @Test
    void aRetakeCarriesItsReasonCodeAndAFinishedPaperNone() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID retake = doc(context, p, "2026-10-19T09:00:00Z", "2026-10-18", "lab_report", "NEEDS_RETAKE");
            UUID done = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-17", "lab_report", "DONE");
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).collectList().block();
            assertThat(rows).extracting(TimelineEntry::documentId).containsExactly(retake, done);
            assertThat(rows.get(0).retakeReasonCode()).isEqualTo("low_overall");
            assertThat(rows.get(0).status()).isEqualTo("NEEDS_RETAKE");
            assertThat(rows.get(1).retakeReasonCode()).isNull();
        });
    }

    /** The other direction of D27: a ROUTINE paper and an unassessed one have no chip. */
    @Test
    void routineAndUnassessedPapersHaveNoChip() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID routine = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(routine, "routine");
            doc(context, p, "2026-10-19T09:00:00Z", "2026-10-17", "lab_report", "DONE");   // never assessed
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).collectList().block();
            assertThat(rows).hasSize(2).allSatisfy(e -> assertThat(e.chip()).isEmpty());
            assertThat(rows.get(0).urgency()).isEqualTo(Urgency.ROUTINE);
            assertThat(rows.get(1).urgency()).isNull();
        });
    }

    @Test
    void theLabFilterExcludesPrescriptionsAndThePrescriptionFilterTheLabs() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID lab = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            UUID imaging = doc(context, p, "2026-10-19T08:00:01Z", "2026-10-17", "imaging_report", "DONE");
            UUID rx = doc(context, p, "2026-10-19T08:00:02Z", "2026-10-16", "prescription", "DONE");
            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            assertThat(port.page(p, TimelineCursor.FIRST, TimelineFilter.LAB, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(lab, imaging).doesNotContain(rx);
            assertThat(port.page(p, TimelineCursor.FIRST, TimelineFilter.PRESCRIPTION, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(rx);
            assertThat(port.page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(lab, imaging, rx);
        });
    }

    /** One patient's timeline never shows a sibling's paper (the read port filters on patient_id). */
    @Test
    void aPageHoldsOnlyThisPatientsPapers() {
        runner().run(context -> {
            UUID ma = patient(context);
            UUID other = patient(context);
            UUID mine = doc(context, ma, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            UUID theirs = doc(context, other, "2026-10-19T09:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(theirs, "now");
            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            assertThat(port.page(ma, TimelineCursor.FIRST, TimelineFilter.ALL, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(mine);
            assertThat(port.attention(ma, NOW.minus(Duration.ofDays(30)), 5).collectList().block()).isEmpty();
        });
    }

    /** s06 and the s16 strip: NOW and THIS_WEEK only, in ANY review state, NOW first, newest within a level. */
    @Test
    void attentionHoldsNowAndThisWeekWhateverTheReviewState() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID nowPending = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(nowPending, "now");
            explanation(nowPending, "pending");
            UUID week = doc(context, p, "2026-10-19T09:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(week, "this_week");
            explanation(week, "released");
            UUID routine = doc(context, p, "2026-10-19T10:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(routine, "routine");
            explanation(routine, "released");
            doc(context, p, "2026-10-19T11:00:00Z", "2026-10-18", "lab_report", "DONE");   // unassessed
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(nowPending, week);
        });
    }

    /** RECENT_LIMIT's defect: 51 newer routine papers cannot push an older NOW paper off the stack (filtered in SQL). */
    @Test
    void manyNewerRoutinePapersDoNotHideANowPaper() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID now = doc(context, p, "2026-10-01T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            urgency(now, "now");
            for (int i = 0; i < 51; i++) {
                UUID r = doc(context, p, "2026-10-19T08:00:" + String.format("%02d", i) + "Z", "2026-10-19", "lab_report", "DONE");
                urgency(r, "routine");
            }
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(now);
        });
    }

    /** The 30-day window (spec §4.6), both edges: uploaded exactly 30 days ago is in; one second earlier is out. */
    @Test
    void theAttentionWindowIncludesItsStartAndNothingBefore() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID edge = doc(context, p, NOW.minus(Duration.ofDays(30)).toString(), "2026-09-20", "lab_report", "DONE");
            urgency(edge, "this_week");
            UUID before = doc(context, p, NOW.minus(Duration.ofDays(30)).minusSeconds(1).toString(), "2026-09-20", "lab_report", "DONE");
            urgency(before, "now");
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(edge);
        });
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
