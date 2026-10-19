package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F5 on real Postgres (V20). Why a document needs a retake is a machine code, and the database holds the rule every
 * writer meets (the V14 lesson): a code only on a retake, every retake has one, and only the known forms. V20's own
 * backfill, fed the code's own prose constants, leaves no retake without a code, so both CHECKs are valid — never
 * {@code NOT VALID}, which a later write to an old row could trip. And {@code timeline_date}, the key C2 orders by.
 */
@Testcontainers
class DocumentReasonCodeConstraintTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    /** Each V20 constraint by name, both directions. */
    @Test
    void everyReasonCodeConstraintRefusesItsBadWriteAndAllowsItsGoodOne() {
        runner().run(context -> {
            UUID doc = receivedDocument(context);
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code='blurry' WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_status_reason_code_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='DONE', status_reason_code='no_text' WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_reason_code_only_on_retake");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code=NULL WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_retake_has_reason_code");
            sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code='low_section:values' WHERE id='" + doc + "'");
            assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + doc + "'")).isEqualTo("low_section:values");
            // and back off a retake: the code goes with it
            sql("UPDATE baymax.document SET status='PROCESSING', status_reason_code=NULL WHERE id='" + doc + "'");
            assertThat(scalar("SELECT status_reason_code IS NULL FROM baymax.document WHERE id='" + doc + "'")).isEqualTo("t");
        });
    }

    /** The backfill, fed the code's own constants (so the SQL and the Java cannot drift), plus an unmatched legacy prose. */
    @Test
    void theBackfillMapsTheFourProseConstantsAndMarksAnythingElseLegacy() {
        runner().run(context -> {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(DocumentExtractionService.REASON_NO_TEXT, "no_text");
            expected.put(DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall");
            expected.put(DocumentExtractionService.REASON_SECTION_EMPTY.formatted("medicines", "prescription"), "expected_section_empty:medicines");
            expected.put(DocumentExtractionService.REASON_SECTION_EMPTY.formatted("follow_up", "prescription"), "expected_section_empty:follow_up");
            expected.put(DocumentExtractionService.REASON_LOW_SECTION + " (0.55)", "low_section:unknown");
            expected.put("an old reason from 2025", "legacy");
            // the table as it was before V20's constraints: drop both, write pre-V20 rows, run V20's own blocks again
            sql("ALTER TABLE baymax.document DROP CONSTRAINT document_reason_code_only_on_retake, DROP CONSTRAINT document_retake_has_reason_code");
            Map<String, UUID> rows = new LinkedHashMap<>();
            for (String prose : expected.keySet()) {
                UUID doc = receivedDocument(context);
                sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason='" + prose.replace("'", "''")
                        + "', status_reason_code=NULL WHERE id='" + doc + "'");
                rows.put(prose, doc);
            }
            // a FAILED document keeps its prose and gets no code: the backfill touches retakes only
            UUID failed = receivedDocument(context);
            sql("UPDATE baymax.document SET status='FAILED', status_reason='groq HTTP 503' WHERE id='" + failed + "'");

            sql(block("backfill"));
            sql(block("constraint"));   // ADD CONSTRAINT validates every row: a NULL code left by the backfill fails here

            for (Map.Entry<String, String> e : expected.entrySet()) {
                assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + rows.get(e.getKey()) + "'"))
                        .as(e.getKey()).isEqualTo(e.getValue());
            }
            assertThat(scalar("SELECT status_reason_code IS NULL FROM baymax.document WHERE id='" + failed + "'")).isEqualTo("t");
            // both constraints are back and VALID, not NOT VALID
            assertThat(scalar("SELECT count(*) FROM pg_constraint WHERE conrelid = 'baymax.document'::regclass AND convalidated "
                    + "AND conname IN ('document_reason_code_only_on_retake', 'document_retake_has_reason_code')")).isEqualTo("2");
        });
    }

    /** The app's own writes to a backfilled legacy retake succeed: the constraint is valid and the row satisfies it. */
    @Test
    void anAppWriteToALegacyRetakeRowSucceeds() {
        runner().run(context -> {
            UUID doc = receivedDocument(context);
            sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason='an old reason', status_reason_code='legacy' WHERE id='" + doc + "'");
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Document d = documents.find(doc).block();
            assertThat(d.statusReasonCode()).as("the entity reads the column").isEqualTo("legacy");
            documents.update(d.withStatus(Document.Status.NEEDS_RETAKE, d.statusReason(), d.statusReasonCode(), Instant.now())).block();
            assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + doc + "'")).isEqualTo("legacy");
        });
    }

    /** The port binds the code: what a retake path writes is what is stored, and a retake without one is refused. */
    @Test
    void thePortWritesTheCodeAndTheDatabaseRefusesARetakeWithoutOne() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Document d = documents.find(receivedDocument(context)).block();

            documents.update(d.withStatus(Document.Status.NEEDS_RETAKE,
                    DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall", T)).block();
            assertThat(documents.find(d.id()).block().statusReasonCode()).isEqualTo("low_overall");

            assertThatThrownBy(() -> documents.update(d.withStatus(Document.Status.NEEDS_RETAKE, "a retake", T)).block())
                    .hasMessageContaining("document_retake_has_reason_code");
            documents.update(d.withStatus(Document.Status.DONE, null, T)).block();   // off a retake, the code is cleared
            assertThat(scalar("SELECT status || '|' || coalesce(status_reason_code, 'NULL') FROM baymax.document WHERE id='" + d.id() + "'"))
                    .isEqualTo("DONE|NULL");
        });
    }

    /** The timeline key: the printed date, else the Dhaka day of the upload (F7) — generated, so sort and label agree. */
    @Test
    void theTimelineDateIsThePrintedDateElseTheDhakaUploadDay() {
        runner().run(context -> {
            UUID dated = receivedDocument(context);
            UUID undated = receivedDocument(context);
            sql("UPDATE baymax.document SET doc_date='2026-07-12' WHERE id='" + dated + "'");
            sql("UPDATE baymax.document SET doc_date=NULL, created_at='2026-10-18T19:30:00Z' WHERE id='" + undated + "'");   // 01:30 on the 19th in Dhaka
            assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id='" + dated + "'")).isEqualTo("2026-07-12");
            assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id='" + undated + "'")).isEqualTo("2026-10-19");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET timeline_date='2026-01-01' WHERE id='" + dated + "'"))
                    .as("generated: nothing writes it").hasMessageContaining("timeline_date");
        });
    }

    /** A RECEIVED document for a new family and patient (fixture numbers in +8801700000861..899). */
    private static UUID receivedDocument(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null,
                "+8801700000" + (860 + FAMILIES.incrementAndGet()), "Tanvir", FamilyAccount.Plan.FREE, T, T)).block();
        PatientProfile patient = records.createPatient(
                new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
        return context.getBean(DocumentRecordPort.class).create(Document.received(patient.id(), family.id(), 1, T)).block().id();
    }

    /** The text between {@code -- <name>:begin} and {@code -- <name>:end} of V20, read from the classpath: the shipped SQL. */
    private static String block(String name) throws Exception {
        String v20 = new String(DocumentReasonCodeConstraintTest.class
                .getResourceAsStream("/db/baymax/migration/V20__retake_reason_code_and_timeline_date.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        int start = v20.indexOf("-- " + name + ":begin");
        int end = v20.indexOf("-- " + name + ":end");
        assertThat(start).as("V20 must mark its %s block", name).isNotNegative();
        assertThat(end).as("V20 must end its %s block", name).isGreaterThan(start);
        String text = v20.substring(start, end).replace("${flyway:defaultSchema}", "baymax");
        assertThat(text).as("the %s block holds a statement on document", name).contains("baymax.document");
        return text;
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.urgency.sweep-cron=-",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
