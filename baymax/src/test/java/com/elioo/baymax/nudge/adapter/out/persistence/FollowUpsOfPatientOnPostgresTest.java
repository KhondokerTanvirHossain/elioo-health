package com.elioo.baymax.nudge.adapter.out.persistence;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The চ্যাট attention stack's follow-ups (C4, spec §4.6), on real Postgres: an open follow-up the doctor wrote for one
 * patient, dated today .. today + 7, soonest first. Both window edges are written out, so a half-open window or a
 * one-sided bound is red. Each test makes its own families, so the tests run in any order against the one container.
 */
@Testcontainers
class FollowUpsOfPatientOnPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-10-19T04:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-19");

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    /** s06: an open follow-up the doctor wrote for today .. today+7, soonest first; nothing outside, closed or another patient's. */
    @Test
    void openFollowUpsOfOnePatientInsideTheWindowSoonestFirst() {
        runner().run(context -> {
            NudgeDataPort data = context.getBean(NudgeDataPort.class);
            PatientProfile ma = patient(context, "+8801700000711");
            PatientProfile other = patient(context, "+8801700000712");
            UUID rx = doc(context, ma, "prescription", "DONE");
            UUID otherRx = doc(context, other, "prescription", "DONE");
            followUp(ma, rx, "CBC after 7 days", TODAY.plusDays(7), "open");         // in: the far edge
            followUp(ma, rx, "Next visit: 20/10/26", TODAY.plusDays(1), "open");     // in
            followUp(ma, rx, "Come today", TODAY, "open");                           // in: the near edge
            followUp(ma, rx, "Echo after 8 days", TODAY.plusDays(8), "open");        // out: one past
            followUp(ma, rx, "Visit yesterday", TODAY.minusDays(1), "open");         // out: before today
            followUp(ma, rx, "Done visit", TODAY.plusDays(2), "done");               // out: closed
            followUp(ma, rx, "Cancelled visit", TODAY.plusDays(2), "cancelled");     // out: closed
            followUp(ma, rx, "Some day", null, "open");                              // out: no date written
            followUp(other, otherRx, "Other patient", TODAY.plusDays(2), "open");    // out: not Ma
            assertThat(data.openFollowUpsOfPatient(ma.id(), TODAY, TODAY.plusDays(7)).map(NudgeDataPort.FollowUpRow::instruction)
                    .collectList().block()).containsExactly("Come today", "Next visit: 20/10/26", "CBC after 7 days");
        });
    }

    /** The row carries what the card draws: the document (for the link and the crop), the date and the crop key. */
    @Test
    void theRowCarriesItsDocumentItsDateAndItsCrop() {
        runner().run(context -> {
            PatientProfile ma = patient(context, "+8801700000713");
            UUID rx = doc(context, ma, "prescription", "DONE");
            followUp(ma, rx, "Next visit: 20/10/26", TODAY.plusDays(1), "open");
            NudgeDataPort.FollowUpRow row = context.getBean(NudgeDataPort.class)
                    .openFollowUpsOfPatient(ma.id(), TODAY, TODAY.plusDays(7)).single().block();
            assertThat(row.documentId()).isEqualTo(rx);
            assertThat(row.patientId()).isEqualTo(ma.id());
            assertThat(row.familyId()).isEqualTo(ma.familyId());
            assertThat(row.dueDate()).isEqualTo(TODAY.plusDays(1));
            assertThat(row.cropKey()).isEqualTo("f/p/" + rx + "/crop-f.jpg");
        });
    }

    /**
     * Only a finished paper's follow-ups: a paper being read again (recovery) is the "being read" card, never a date the
     * stack shows from it meanwhile. The DONE paper beside it keeps its follow-up, so the filter is not simply "none".
     */
    @Test
    void aFollowUpOfAPaperThatIsNotDoneIsNotOnTheStack() {
        runner().run(context -> {
            PatientProfile ma = patient(context, "+8801700000714");
            UUID done = doc(context, ma, "prescription", "DONE");
            UUID rereading = doc(context, ma, "prescription", "PROCESSING");
            followUp(ma, done, "Next visit: 20/10/26", TODAY.plusDays(1), "open");
            followUp(ma, rereading, "Echo on Monday", TODAY.plusDays(2), "open");
            assertThat(scalar("SELECT count(*) FROM baymax.follow_up WHERE patient_id = '" + ma.id() + "' AND status = 'open'"))
                    .as("both follow-ups are really open").isEqualTo("2");
            assertThat(context.getBean(NudgeDataPort.class).openFollowUpsOfPatient(ma.id(), TODAY, TODAY.plusDays(7))
                    .map(NudgeDataPort.FollowUpRow::instruction).collectList().block()).containsExactly("Next visit: 20/10/26");
        });
    }

    /** A fresh family (fictional number in the fixture range) with one patient. */
    private static PatientProfile patient(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)).block();
    }

    /** A document of this patient, created through the port, then set to this type and status in SQL. */
    private static UUID doc(ApplicationContext context, PatientProfile p, String type, String status) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(p.id(), p.familyId(), 1, NOW)).block().id();
        sql("UPDATE baymax.document SET status = '" + status + "', document_type = '" + type + "' WHERE id = '" + id + "'");
        return id;
    }

    private static void followUp(PatientProfile p, UUID document, String instruction, LocalDate due, String status) throws Exception {
        sql("INSERT INTO baymax.follow_up (patient_id, document_id, instruction, due_date, status, crop_key) VALUES ('"
                + p.id() + "','" + document + "','" + instruction + "'," + (due == null ? "NULL" : "'" + due + "'") + ",'" + status
                + "','f/p/" + document + "/crop-f.jpg')");
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
