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
import java.time.YearMonth;
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

    /**
     * All three parts of the cursor, across pages. Three printed dates whose uploads run the other way (the oldest paper
     * was sent last), and on each date ids that run against the upload order, with two uploads sharing an instant. Paged
     * five at a time from the last row shown, the pages joined must be exactly the one ORDER BY over every row: none
     * skipped, none repeated. A cursor in upload order, or one that drops the upload instant, loses rows at the join.
     */
    @Test
    void pagingThroughPapersWhoseDatesUploadsAndIdsDisagreeYieldsEveryRowOnceInOrder() {
        runner().run(context -> {
            UUID p = patient(context);
            String tail = UUID.randomUUID().toString().substring(8);   // unique to this test; the rank in front sets the id order
            String[] dates = {"2026-10-12", "2026-09-12", "2026-08-12"};
            String[] uploadDays = {"2026-10-17", "2026-10-18", "2026-10-19"};   // the oldest paper uploaded last
            int rank = 0;
            for (int d = 0; d < dates.length; d++) {
                for (int slot = 0; slot < 8; slot++) {
                    int hour = 8 + (slot == 4 ? 3 : slot);   // slots 3 and 4 share one instant: the id alone orders them
                    UUID made = doc(context, p, uploadDays[d] + "T" + String.format("%02d", hour) + ":00:00Z", dates[d], "lab_report", "DONE");
                    // within a date, the later the upload the SMALLER the id, so the id order runs against the upload order
                    UUID id = UUID.fromString(String.format("%08x", 1000 + d * 100 + (100 - slot)) + tail);
                    sql("UPDATE baymax.document SET id = '" + id + "' WHERE id = '" + made + "'");
                    rank++;
                }
            }
            List<String> expected = ids("ORDER BY timeline_date DESC, created_at DESC, id DESC", p);
            // the data really exercises every part of the cursor: neither of the broken orders agrees with the true one
            assertThat(expected).hasSize(rank).isNotEqualTo(ids("ORDER BY created_at DESC, id DESC", p))
                    .isNotEqualTo(ids("ORDER BY timeline_date DESC, id DESC", p));

            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            List<String> seen = new java.util.ArrayList<>();
            TimelineCursor after = TimelineCursor.FIRST;
            for (int pages = 0; pages < 20; pages++) {
                List<TimelineEntry> page = port.page(p, after, TimelineFilter.ALL, 5).collectList().block();
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(e -> seen.add(e.documentId().toString()));
                TimelineEntry last = page.get(page.size() - 1);
                after = new TimelineCursor(last.date(), last.createdAt(), last.documentId());
            }
            assertThat(seen).as("every paper exactly once, in the list's order, across the page joins").containsExactlyElementsOf(expected);
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

    /**
     * D2: a retake is a page the model could not read confidently, so the date it read off the page is the least
     * trustworthy date there is. It is filed under the day it was uploaded, in Dhaka, and says so; the printed date
     * stays stored (a later reading may confirm it) but is never the paper's date.
     */
    @Test
    void aRetakeIsFiledUnderItsUploadDayNotTheDateReadOffIt() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID retake = doc(context, p, "2026-10-20T04:00:00Z", "2021-03-04", "lab_report", "NEEDS_RETAKE");
            TimelineEntry e = only(context, p);
            assertThat(e.documentId()).isEqualTo(retake);
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-10-20"));
            assertThat(e.dateIsFallback()).isTrue();
            assertThat(scalar("SELECT doc_date FROM baymax.document WHERE id = '" + retake + "'"))
                    .as("the printed date is kept, only never shown").isEqualTo("2021-03-04");
        });
    }

    /** Review Focus 5: 18:30 UTC on 31 October is 00:30 on 1 November in Dhaka, so the retake is in November. */
    @Test
    void aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth() {
        runner().run(context -> {
            UUID p = patient(context);
            doc(context, p, "2026-10-31T18:30:00Z", "2026-10-12", "lab_report", "NEEDS_RETAKE");
            TimelineEntry e = only(context, p);
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-11-01"));
            assertThat(YearMonth.from(e.date())).isEqualTo(YearMonth.parse("2026-11"));
            assertThat(e.dateIsFallback()).isTrue();
        });
    }

    /** The other edge: 17:59:59 UTC is 23:59:59 in Dhaka, still 31 October. */
    @Test
    void aRetakeUploadedJustBeforeDhakaMidnightStaysInTheOldMonth() {
        runner().run(context -> {
            UUID p = patient(context);
            doc(context, p, "2026-10-31T17:59:59Z", "2026-10-12", "lab_report", "NEEDS_RETAKE");
            TimelineEntry e = only(context, p);
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-10-31"));
            assertThat(YearMonth.from(e.date())).isEqualTo(YearMonth.parse("2026-10"));
        });
    }

    /** The other direction: a paper read confidently keeps its printed date, whatever day it was uploaded. */
    @Test
    void aFinishedPaperKeepsItsPrintedDateAcrossMidnight() {
        runner().run(context -> {
            UUID p = patient(context);
            doc(context, p, "2026-10-31T18:30:00Z", "2026-09-30", "lab_report", "DONE");
            TimelineEntry e = only(context, p);
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-09-30"));
            assertThat(e.dateIsFallback()).isFalse();
            assertThat(YearMonth.from(e.date())).isEqualTo(YearMonth.parse("2026-09"));
        });
    }

    /** Q6: a printed date the model marked uncertain is filed as printed, and marked; the plain paper beside it is not. */
    @Test
    void anUncertainPrintedDateIsFiledAsPrintedAndMarked() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID uncertain = doc(context, p, "2026-10-20T04:00:00Z", "2026-07-11", "lab_report", "DONE");
            sql("UPDATE baymax.document SET doc_date_uncertain = true WHERE id = '" + uncertain + "'");
            UUID plain = doc(context, p, "2026-10-20T04:00:00Z", "2026-07-10", "lab_report", "DONE");
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10)
                    .collectList().block();
            assertThat(rows).extracting(TimelineEntry::documentId).containsExactly(uncertain, plain);
            assertThat(rows.get(0).date()).isEqualTo(LocalDate.parse("2026-07-11"));
            assertThat(rows.get(0).dateIsFallback()).isFalse();
            assertThat(rows.get(0).dateUncertain()).isTrue();
            assertThat(rows.get(1).dateUncertain()).isFalse();
        });
    }

    /**
     * Q6's other direction: the mark is on a printed date the paper is filed under, never on an upload day. The model may
     * mark the date of a page it could not read (a retake), or mark a date it then returned as null; either paper is
     * filed under its upload day, which is certain, so it is not marked and a trend keeps its direction for it.
     */
    @Test
    void anUncertainMarkNeverLandsOnAnUploadDay() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID retake = doc(context, p, "2026-10-20T04:00:00Z", "2021-03-04", "lab_report", "NEEDS_RETAKE");
            UUID undated = doc(context, p, "2026-10-19T04:00:00Z", null, "lab_report", "DONE");
            sql("UPDATE baymax.document SET doc_date_uncertain = true WHERE id IN ('" + retake + "', '" + undated + "')");
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10)
                    .collectList().block();
            assertThat(rows).extracting(TimelineEntry::documentId).containsExactly(retake, undated);
            assertThat(rows).allSatisfy(e -> {
                assertThat(e.dateIsFallback()).as("filed under its upload day").isTrue();
                assertThat(e.dateUncertain()).as("an upload day is never marked uncertain").isFalse();
            });
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + p + "' AND doc_date_uncertain"))
                    .as("the model's own flag is still stored on both").isEqualTo("2");
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

    /**
     * The other direction of "DONE only": a paper still being read, or one waiting for a retake, never reaches the stack
     * even with a stored NOW. The finished NOW paper beside them is there, so an empty answer cannot pass.
     */
    @Test
    void aPaperThatIsNotDoneIsNeverOnTheAttentionStack() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID done = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(done, "now");
            for (String status : List.of("NEEDS_RETAKE", "PROCESSING", "RECEIVED", "FAILED")) {
                UUID notDone = doc(context, p, "2026-10-19T09:00:00Z", "2026-10-19", "lab_report", status);
                urgency(notDone, "now");
            }
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE patient_id = '" + p + "' AND urgency = 'now' AND status <> 'DONE'"))
                    .as("the not-DONE papers really carry a stored NOW").isEqualTo("4");
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(done);
        });
    }

    /** Newest document date first within a level, whatever the upload order: the older paper was sent later. */
    @Test
    void withinALevelTheNewerDocumentDateComesFirst() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID newerPaper = doc(context, p, "2026-10-10T08:00:00Z", "2026-10-09", "lab_report", "DONE");
            urgency(newerPaper, "now");
            UUID olderPaper = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            urgency(olderPaper, "now");
            UUID newerWeek = doc(context, p, "2026-10-11T08:00:00Z", "2026-10-08", "lab_report", "DONE");
            urgency(newerWeek, "this_week");
            UUID olderWeek = doc(context, p, "2026-10-18T08:00:00Z", "2026-10-02", "lab_report", "DONE");
            urgency(olderWeek, "this_week");
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(newerPaper, olderPaper, newerWeek, olderWeek);
        });
    }

    /** "Any review state" over every gate state V9 allows: the stack shows the level whatever the reviewer did. */
    @Test
    void attentionHoldsANowPaperInEveryGateState() {
        runner().run(context -> {
            UUID p = patient(context);
            List<String> gates = List.of("released", "pending", "approved", "rejected", "failed_safety");
            List<UUID> expected = new java.util.ArrayList<>();
            for (int i = 0; i < gates.size(); i++) {
                // later index = newer document date, so the expected order is the reverse of insertion
                UUID d = doc(context, p, "2026-10-19T08:00:0" + i + "Z", "2026-10-1" + i, "lab_report", "DONE");
                urgency(d, "now");
                explanation(d, gates.get(i));
                expected.add(0, d);
            }
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 10)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactlyElementsOf(expected);
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

    /**
     * C4's "being read" and "waiting for a retake" cards: RECEIVED, PROCESSING and NEEDS_RETAKE papers uploaded at or after
     * {@code since}, newest upload first. Never a DONE or a FAILED one, never one a second before the window, never
     * another patient's. Both window edges and every status V5 allows are written out.
     */
    @Test
    void inFlightAndRetakesAreTheUnfinishedPapersInsideTheWindowNewestFirst() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID other = patient(context);
            Instant since = NOW.minus(Duration.ofDays(30));
            UUID edge = doc(context, p, since.toString(), null, null, "RECEIVED");                    // in: the window's start
            UUID retake = doc(context, p, "2026-10-18T08:00:00Z", null, "lab_report", "NEEDS_RETAKE"); // in
            UUID reading = doc(context, p, "2026-10-20T03:00:00Z", null, null, "PROCESSING");          // in: the newest
            doc(context, p, since.minusSeconds(1).toString(), null, null, "PROCESSING");               // out: a second early
            doc(context, p, "2026-10-19T08:00:00Z", "2026-10-19", "lab_report", "DONE");               // out: finished
            doc(context, p, "2026-10-19T09:00:00Z", null, null, "FAILED");                            // out: not coming
            doc(context, other, "2026-10-19T10:00:00Z", null, null, "PROCESSING");                     // out: not this patient
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).inFlightAndRetakes(p, since).collectList().block();
            assertThat(rows).extracting(TimelineEntry::documentId).containsExactly(reading, retake, edge);
            assertThat(rows).extracting(TimelineEntry::status).containsExactly("PROCESSING", "NEEDS_RETAKE", "RECEIVED");
            assertThat(rows.get(1).retakeReasonCode()).as("the retake keeps its F5 code").isEqualTo("low_overall");
        });
    }

    /** The patient's one paper, through the timeline read port. */
    private static TimelineEntry only(ApplicationContext context, UUID patient) {
        List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(patient, TimelineCursor.FIRST, TimelineFilter.ALL, 10)
                .collectList().block();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    /** This patient's document ids in {@code orderBy}, straight from Postgres: the reference a paged read must equal. */
    private static List<String> ids(String orderBy, UUID patient) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM baymax.document WHERE patient_id = '" + patient + "' " + orderBy)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
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
