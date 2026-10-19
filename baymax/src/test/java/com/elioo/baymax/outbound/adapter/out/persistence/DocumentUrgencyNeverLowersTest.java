package com.elioo.baymax.outbound.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 on real Postgres. A document's urgency only rises. The rule lives in a trigger, so no writer, present or future,
 * can store a lower level or drop a reason (the V14 lesson: assert the invariant once, where every writer meets it).
 * The port additionally merges as max(old, new), so a correct writer never trips the trigger.
 */
@Testcontainers
class DocumentUrgencyNeverLowersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    @Test
    void aDirectUpdateThatLowersOrClearsUrgencyIsRejectedByTheDatabase() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            context.getBean(DocumentUrgencyPort.class).raise(doc,
                    new UrgencyAssessment(Urgency.THIS_WEEK, List.of("value_outside_range:ldl|stopgap")), T).block();

            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'routine' WHERE id = '" + doc + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("document urgency may not be lowered (this_week -> routine)");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = NULL, urgency_reasons = NULL, "
                    + "urgency_assessed_at = NULL WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document urgency may not be lowered (this_week -> NULL)");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency_reasons = '[]' WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document urgency reasons may not be removed");
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("this_week");
        });
    }

    /** The direction that must still work: a first assessment at any level, and raising, are allowed. */
    @Test
    void anUnassessedDocumentTakesAnyLevelAndALevelCanRise() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            assertThat(port.raise(doc, UrgencyAssessment.routine(), T).block().level()).isEqualTo(Urgency.ROUTINE);
            assertThat(port.raise(doc, new UrgencyAssessment(Urgency.THIS_WEEK, List.of("follow_up_past_due")), T)
                    .block().level()).isEqualTo(Urgency.THIS_WEEK);
            sql("UPDATE baymax.document SET urgency = 'now' WHERE id = '" + doc + "'");       // raising directly is fine
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("now");
        });
    }

    @Test
    void thePortKeepsTheHigherLevelAndTheUnionOfReasonsWhenALaterAssessmentIsLower() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(doc, new UrgencyAssessment(Urgency.NOW, List.of("value_critical:potassium|stopgap")), T).block();

            StoredUrgency after = port.raise(doc, new UrgencyAssessment(Urgency.ROUTINE, List.of()), T.plusSeconds(60)).block();

            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.reasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(after.assessedAt()).isEqualTo(T.plusSeconds(60));
            // the writer must not stamp updated_at: ExplanationService.composedAfterLastChange keys the DETAIL cache on it
            assertThat(scalar("SELECT updated_at = created_at FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("t");
        });
    }

    /** A marker name carries a comma ("cholesterol, total"): reasons are a JSON array, never a comma-joined string. */
    @Test
    void aReasonWithACommaRoundTripsAsOneReason() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            StoredUrgency s = context.getBean(DocumentUrgencyPort.class).raise(doc,
                    new UrgencyAssessment(Urgency.THIS_WEEK, List.of("value_outside_range:cholesterol, total|stopgap")), T).block();
            assertThat(s.reasons()).containsExactly("value_outside_range:cholesterol, total|stopgap");
            assertThat(context.getBean(DocumentUrgencyPort.class).find(doc).block().reasons())
                    .containsExactly("value_outside_range:cholesterol, total|stopgap");
        });
    }

    /**
     * The backfill. Until now urgency lived only on outbound rows. A document with a ROUTINE explanation and a NOW
     * detail comes out NOW, with reasons from both encodings of outbound_message.urgency_reasons (JSON array since
     * PR 5, comma-joined before, split and trimmed). Only explanation and detail rows feed it: a retake or nudge row on
     * a document is not what the family was told about that paper. A DONE document with no message stays NULL, for
     * the sweep to assess.
     */
    @Test
    void theBackfillTakesTheHighestLevelAFamilyWasToldAndLeavesAnUnmessagedDocumentNull() {
        runner().run(context -> {
            UUID told = doneDocument(context);
            UUID untold = doneDocument(context);
            UUID otherKinds = doneDocument(context);
            String family = scalar("SELECT family_id FROM baymax.document WHERE id = '" + told + "'");
            String otherFamily = scalar("SELECT family_id FROM baymax.document WHERE id = '" + otherKinds + "'");
            sql("UPDATE baymax.document SET urgency = NULL, urgency_reasons = NULL, urgency_assessed_at = NULL "
                    + "WHERE id IN ('" + told + "','" + untold + "','" + otherKinds + "')");   // all start unassessed (NULL -> NULL is not a lowering)
            sql("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status, created_at) VALUES "
                    + "('" + family + "','" + told + "','explanation','routine','diagnosis_present, follow_up_past_due','released','2026-10-01T08:00:00Z'),"
                    + "('" + family + "','" + told + "','detail','now','[\"value_critical:cholesterol, total|stopgap\"]','released','2026-10-02T08:00:00Z'),"
                    + "('" + otherFamily + "','" + otherKinds + "','explanation','routine','','released','2026-10-01T08:00:00Z'),"
                    + "('" + otherFamily + "','" + otherKinds + "','retake','now','[\"text_emergency_phrase\"]','released','2026-10-03T08:00:00Z'),"
                    + "('" + otherFamily + "','" + otherKinds + "','nudge','this_week','follow_up_past_due','released','2026-10-04T08:00:00Z')");

            sql(backfillStatementFromV18());

            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + told + "'")).isEqualTo("now");
            assertThat(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + told + "'"))
                    .isEqualTo("[\"diagnosis_present\", \"follow_up_past_due\", \"value_critical:cholesterol, total|stopgap\"]");
            assertThat(scalar("SELECT urgency_assessed_at = '2026-10-02T08:00:00Z' FROM baymax.document WHERE id = '" + told + "'"))
                    .isEqualTo("t");
            assertThat(scalar("SELECT coalesce(urgency, '<null>') FROM baymax.document WHERE id = '" + untold + "'"))
                    .isEqualTo("<null>");
            // the exclusion direction: the retake's NOW and the nudge's THIS_WEEK do not reach the document
            assertThat(scalar("SELECT urgency || ' ' || urgency_reasons::text FROM baymax.document WHERE id = '" + otherKinds + "'"))
                    .isEqualTo("routine []");
        });
    }

    /** V18's CHECKs, both directions: level, reasons and time together or not at all; a known level; a JSON array. */
    @Test
    void levelReasonsAndTimeAreSetTogetherAsAKnownLevelAndAnArray() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'now' WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document_urgency_complete");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'urgent', urgency_reasons = '[]', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'")).hasMessageContaining("document_urgency_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'now', urgency_reasons = '{\"a\":1}', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'")).hasMessageContaining("document_urgency_reasons_check");
            sql("UPDATE baymax.document SET urgency = 'now', urgency_reasons = '[\"text_emergency_phrase\"]', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'");                       // the good write
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("now");
        });
    }

    /** The V14 lesson: every other writer of the row (writeDocument's explicit UPDATE) meets the trigger and keeps the level. */
    @Test
    void theDocumentsOtherWritersKeepTheStoredLevelAndReasons() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(doc, new UrgencyAssessment(Urgency.NOW, List.of("text_emergency_phrase")), T).block();
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Document d = documents.find(doc).block();
            documents.update(d.withStatus(Document.Status.DONE, null, T.plusSeconds(5))).block();
            StoredUrgency after = port.find(doc).block();
            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.reasons()).containsExactly("text_emergency_phrase");
        });
    }

    /** An unassessed document has nothing stored: find() is empty, never a ROUTINE it was not given. */
    @Test
    void anUnassessedDocumentHasNoStoredUrgency() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            assertThat(context.getBean(DocumentUrgencyPort.class).find(doc).blockOptional()).isEmpty();
        });
    }

    private UUID doneDocument(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null,
                "+88017000007" + String.format("%02d", FAMILIES.incrementAndGet()), "Tanvir", FamilyAccount.Plan.FREE, T, T)).block();
        PatientProfile patient = records.createPatient(
                new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
        DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
        Document received = documents.create(Document.received(patient.id(), family.id(), 1, T)).block();
        return documents.update(received.withStatus(Document.Status.DONE, null, T)).block().id();
    }

    /** The UPDATE block of V18 itself, read from the classpath: the test runs the shipped SQL, not a copy of it. */
    private static String backfillStatementFromV18() throws Exception {
        String v18 = new String(DocumentUrgencyNeverLowersTest.class
                .getResourceAsStream("/db/baymax/migration/V18__document_stored_urgency.sql").readAllBytes(), StandardCharsets.UTF_8);
        int start = v18.indexOf("-- backfill:begin");
        int end = v18.indexOf("-- backfill:end");
        assertThat(start).as("V18 must mark its backfill block").isNotNegative();
        assertThat(end).as("V18 must end its backfill block").isGreaterThan(start);
        String block = v18.substring(start, end).replace("${flyway:defaultSchema}", "baymax");
        assertThat(block).as("the backfill block holds the UPDATE").contains("UPDATE baymax.document");
        return block;
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
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
