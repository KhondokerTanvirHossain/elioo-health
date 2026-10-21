package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.FiledDay;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.testsupport.PaperFixtures;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 on real Postgres, through the ports every reader uses: a document row comes back with the filed day its generated
 * columns hold (V25's timeline_date, timeline_date_is_upload_day, timeline_date_uncertain), the model's flag survives
 * both writes, the latest-paper read never returns a retake, a retake's view never carries its misread date, the
 * prescriptions are ordered by the filed day, and the test fixtures' copy of V25's rule agrees with Postgres. Every
 * expectation is read from the row's own columns AND stated as a literal, so neither a broken mapping nor a changed rule
 * can pass by agreeing with itself.
 */
@Testcontainers
class FilingDayOnPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    /**
     * The session time zone production's driver sends: the container runs in UTC (no TZ in the Dockerfile), and
     * r2dbc-postgresql sends the JVM's zone. On a laptop in Dhaka it would send Asia/Dhaka, and a {@code created_at::date}
     * in SQL would agree with the filed day by accident, so a UTC-day ordering could not fail here for the reason it is wrong.
     */
    static final String PRODUCTION_SESSION_ZONE = "spring.r2dbc.properties.timeZone=UTC";

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES).withPropertyValues(PRODUCTION_SESSION_ZONE);
    }

    /**
     * The three shapes V25 files differently, read back through {@link DocumentRecordPort#find}: a retake with a misread
     * printed date uploaded after Dhaka midnight (its upload day, never the misread date, never marked), a DONE paper
     * whose printed date the model marked uncertain (that date, marked), and a DONE paper that printed none (its Dhaka
     * upload day). Each record's filed day equals the generated columns and the literal day; its stored flag equals
     * {@code doc_date_uncertain}.
     */
    @Test
    void aRowReadsBackWithTheFiledDayItsColumnsHold() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID retake = doc(context, p, "2026-10-31T18:30:00Z", "2021-03-04", "NEEDS_RETAKE", true);
            UUID uncertain = doc(context, p, "2026-10-20T04:00:00Z", "2026-07-11", "DONE", true);
            UUID undated = doc(context, p, "2026-10-18T19:30:00Z", null, "DONE", false);
            DocumentRecordPort port = context.getBean(DocumentRecordPort.class);

            assertFiled(port.find(retake).block(), new FiledDay(LocalDate.parse("2026-11-01"), true, false), true);
            assertFiled(port.find(uncertain).block(), new FiledDay(LocalDate.parse("2026-07-11"), false, true), true);
            assertFiled(port.find(undated).block(), new FiledDay(LocalDate.parse("2026-10-19"), true, false), false);
            UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.document WHERE id = '" + retake + "'"));
            assertThat(port.findVisible(family, uncertain).block().filed()).as("the scoped lookup reads the same row")
                    .isEqualTo(new FiledDay(LocalDate.parse("2026-07-11"), false, true));
        });
    }

    /**
     * Review finding: the WhatsApp greeting names {@code latestDoneDocument}, which is {@code status = 'DONE'} only, so a
     * retake never reaches it. A newer NEEDS_RETAKE with a misread 2021 date sits beside an older DONE paper: the DONE
     * paper comes back, filed under its printed date. For a family whose latest DONE paper printed no date, it is filed
     * under its Dhaka upload day and marked so.
     */
    @Test
    void theLatestDonePaperIsNeverARetake() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID done = doc(context, p, "2026-10-13T08:00:00Z", "2026-10-12", "DONE", false);
            doc(context, p, "2026-10-20T04:00:00Z", "2021-03-04", "NEEDS_RETAKE", false);
            DocumentRecordPort port = context.getBean(DocumentRecordPort.class);
            Document latest = port.latestDoneDocument(familyOf(p)).block();
            assertThat(latest.id()).as("the DONE paper, not the newer retake").isEqualTo(done);
            assertFiled(latest, new FiledDay(LocalDate.parse("2026-10-12"), false, false), false);

            UUID q = patient(context);
            UUID undated = doc(context, q, "2026-10-18T19:30:00Z", null, "DONE", false);
            Document undatedLatest = port.latestDoneDocument(familyOf(q)).block();
            assertThat(undatedLatest.id()).isEqualTo(undated);
            assertFiled(undatedLatest, new FiledDay(LocalDate.parse("2026-10-19"), true, false), false);
        });
    }

    /**
     * The model's flag through both writes and back: {@code update} (a retake's finish) and {@code saveExtraction} (a
     * DONE paper's), each read back from the column and through the port. A retake keeps the model's flag stored but is
     * not marked; the DONE paper with a printed date is marked; a later write of false clears it.
     */
    @Test
    void theUncertainFlagRoundTripsThroughBothWrites() {
        runner().run(context -> {
            UUID p = patient(context);
            DocumentRecordPort port = context.getBean(DocumentRecordPort.class);
            Document received = port.create(Document.received(p, familyOf(p), 1, NOW)).block();

            port.update(written(received, Document.Status.NEEDS_RETAKE, LocalDate.parse("2021-03-04"), "low_overall", true)).block();
            assertThat(scalar("SELECT doc_date_uncertain FROM baymax.document WHERE id = '" + received.id() + "'")).isEqualTo("t");
            Document retake = port.find(received.id()).block();
            assertThat(retake.docDateUncertain()).as("the model's flag, stored on the retake").isTrue();
            assertThat(retake.filed().uncertain()).as("but a retake's upload day is not marked").isFalse();
            assertThat(retake.filed().uploadDay()).isTrue();

            port.saveExtraction(written(received, Document.Status.DONE, LocalDate.parse("2026-07-11"), null, true), VerifiedItems.empty()).block();
            assertThat(scalar("SELECT doc_date_uncertain FROM baymax.document WHERE id = '" + received.id() + "'")).isEqualTo("t");
            Document done = port.find(received.id()).block();
            assertThat(done.docDateUncertain()).isTrue();
            assertThat(done.filed()).isEqualTo(new FiledDay(LocalDate.parse("2026-07-11"), false, true));

            port.update(written(received, Document.Status.DONE, LocalDate.parse("2026-07-11"), null, false)).block();
            assertThat(scalar("SELECT doc_date_uncertain FROM baymax.document WHERE id = '" + received.id() + "'")).isEqualTo("f");
            assertThat(port.find(received.id()).block().filed().uncertain()).isFalse();
        });
    }

    /**
     * Review finding 15: a retake's printed date is kept in doc_date but the view intake builds for it carries none, so
     * neither the legacy {@code /documents/{id}} body ({@code document_date}) nor PaperJson ({@code printed_date}) can
     * show it. The DONE paper beside it carries its printed date, so the read is not simply empty.
     */
    @Test
    void aRetakesViewNeverCarriesItsMisreadDate() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID retake = doc(context, p, "2026-10-20T04:00:00Z", "2021-03-04", "NEEDS_RETAKE", false);
            UUID done = doc(context, p, "2026-10-13T08:00:00Z", "2026-10-12", "DONE", false);
            assertThat(scalar("SELECT doc_date FROM baymax.document WHERE id = '" + retake + "'")).as("the misread date is stored").isEqualTo("2021-03-04");
            DocumentIntakeUseCase intake = context.getBean(DocumentIntakeUseCase.class);
            DocumentView.Read read = intake.read(retake).block();
            assertThat(read.view().status()).isEqualTo("NEEDS_RETAKE");
            assertThat(read.view().documentDate()).as("the retake's view").isNull();
            assertThat(read.document().filed()).isEqualTo(new FiledDay(LocalDate.parse("2026-10-20"), true, false));
            assertThat(intake.view(done).block().documentDate()).as("a DONE paper's view").isEqualTo("2026-10-12");
        });
    }

    /**
     * Review finding 6: the prescriptions medicine_changed compares are ordered by the filed day, the Dhaka day, never the
     * UTC day of created_at. A prescription printed 1 November and uploaded at 10:00 UTC on 31 October, and an undated one
     * uploaded at 18:30 UTC the same day (00:30 on 1 November in Dhaka): both are filed under 1 November, so the later
     * upload is the newer paper. Ordered by {@code COALESCE(doc_date, created_at::date)} the undated one fell on the UTC
     * 31 October and became the older.
     */
    @Test
    void prescriptionsAreOrderedByTheirFiledDayInDhaka() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID printed = prescription(context, p, "2026-10-31T10:00:00Z", "2026-11-01");
            UUID undated = prescription(context, p, "2026-10-31T18:30:00Z", null);
            assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id = '" + undated + "'"))
                    .as("the undated prescription's filed day is the Dhaka day").isEqualTo("2026-11-01");
            List<UUID> order = context.getBean(NudgeDataPort.class).medicationsOfPatient(p)
                    .map(NudgeDataPort.MedicationRow::documentId).distinct().collectList().block();
            assertThat(order).as("newest filed day first, then the later upload").containsExactly(undated, printed);
        });
    }

    /**
     * Review finding 24: {@link PaperFixtures#filedAsStored} is a Java copy of V25's rule that every render and contract
     * test takes its filed day from. It must agree with Postgres on the cases that rule separates: a retake whose view
     * still carries a misread printed date, an undated DONE paper just before Dhaka midnight, and a DONE paper uploaded
     * after Dhaka midnight whose printed date is a month earlier. If V25's rule changes, this fails before any fixture
     * test can agree with a rule production no longer has.
     */
    @Test
    void theFixturesFileAsPostgresDoes() {
        runner().run(context -> {
            UUID p = patient(context);
            DocumentRecordPort port = context.getBean(DocumentRecordPort.class);
            record Case(String createdAt, String printed, String status) {
            }
            for (Case c : List.of(new Case("2026-10-31T18:30:00Z", "2021-03-04", "NEEDS_RETAKE"),
                    new Case("2026-10-31T17:59:59Z", null, "DONE"),
                    new Case("2026-10-31T18:30:00Z", "2026-09-30", "DONE"))) {
                UUID id = doc(context, p, c.createdAt(), c.printed(), c.status(), false);
                DocumentView view = new DocumentView(id.toString(), c.status(), "NEEDS_RETAKE".equals(c.status()) ? "low_overall" : null,
                        "lab_report", c.printed(), null, null, null, 1, null, null, null, null, null);
                assertThat(PaperFixtures.filedAsStored(view, Instant.parse(c.createdAt())))
                        .as("the fixture's filed day for " + c).isEqualTo(port.find(id).block().filed());
            }
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static void assertFiled(Document d, FiledDay expected, boolean flag) throws Exception {
        String[] cols = row("SELECT timeline_date, timeline_date_is_upload_day, timeline_date_uncertain, doc_date_uncertain "
                + "FROM baymax.document WHERE id = '" + d.id() + "'");
        FiledDay fromColumns = new FiledDay(LocalDate.parse(cols[0]), "t".equals(cols[1]), "t".equals(cols[2]));
        assertThat(d.filed()).as("the record's filed day, as its columns hold it").isEqualTo(fromColumns);
        assertThat(d.filed()).as("the record's filed day").isEqualTo(expected);
        assertThat(d.docDateUncertain()).as("the record's stored flag, as doc_date_uncertain holds it").isEqualTo("t".equals(cols[3]));
        assertThat(d.docDateUncertain()).as("the record's stored flag").isEqualTo(flag);
    }

    /** A record as finish writes it: the model's printed date and flag on this status. */
    private static Document written(Document d, Document.Status status, LocalDate printed, String code, boolean uncertain) {
        return new Document(d.id(), d.patientId(), d.familyId(), "lab_report", printed, null, "{}", 0.9, status, "developer prose",
                "anthropic/claude-sonnet-5", null, d.pageCount(), d.createdAt(), NOW, VerifiedItems.Unverified.none(), null, code, uncertain);
    }

    private static UUID patient(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000008" + String.format("%02d", FAMILIES.incrementAndGet()),
                "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)).block().id();
    }

    private static UUID familyOf(UUID patient) throws Exception {
        return UUID.fromString(scalar("SELECT family_id FROM baymax.patient_profile WHERE id = '" + patient + "'"));
    }

    /** A document with exactly these columns (created through the port, then set in SQL so created_at and doc_date are exact). */
    private static UUID doc(ApplicationContext context, UUID patient, String createdAt, String docDate, String status, boolean uncertain) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(patient, familyOf(patient), 1, NOW)).block().id();
        sql("UPDATE baymax.document SET status = '" + status + "', document_type = 'lab_report', created_at = '" + createdAt
                + "', doc_date = " + (docDate == null ? "NULL" : "'" + docDate + "'") + ", doc_date_uncertain = " + uncertain
                + (status.equals("NEEDS_RETAKE") ? ", status_reason_code = 'low_overall', status_reason = 'developer prose'" : "")
                + " WHERE id = '" + id + "'");
        return id;
    }

    /** A DONE prescription with one medicine, uploaded at {@code createdAt}. */
    private static UUID prescription(ApplicationContext context, UUID patient, String createdAt, String docDate) throws Exception {
        UUID id = doc(context, patient, createdAt, docDate, "DONE", false);
        sql("UPDATE baymax.document SET document_type = 'prescription' WHERE id = '" + id + "'");
        sql("INSERT INTO baymax.medication_event (patient_id, document_id, name, crop_key, at) "
                + "SELECT patient_id, id, 'Napa 500', 'k/m.jpg', created_at FROM baymax.document WHERE id = '" + id + "'");
        return id;
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        return row(query)[0];
    }

    private static String[] row(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            String[] out = new String[rs.getMetaData().getColumnCount()];
            for (int i = 0; i < out.length; i++) {
                out[i] = rs.getString(i + 1);
            }
            return out;
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
