package com.elioo.baymax.outbound.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.outbound.domain.ValueVerdict.Kind;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F2 on real Postgres: verdicts never soften, are joined on observation id, and are written with the urgency or not at all. */
@Testcontainers
class ValueVerdictNeverSoftensTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");

    record Fixture(UUID doc, List<UUID> observations) {
        UUID obs(int i) {
            return observations.get(i);
        }
    }

    /** A DONE document (DocumentUrgencyNeverLowersTest.doneDocument) with one observation per "name|value|unit" row, in order. */
    private Fixture documentWithObservations(ApplicationContext context, String... rows) throws Exception {
        UUID doc = DocumentUrgencyNeverLowersTest.doneDocument(context);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            String[] r = rows[i].split("\\|");
            UUID id = UUID.randomUUID();
            sql("INSERT INTO baymax.observation (id, patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) "
                    + "SELECT '" + id + "', patient_id, id, '" + r[0] + "', '" + r[0].toLowerCase(Locale.ROOT) + "', '" + r[1] + "', '"
                    + r[2] + "', 'k/crop-" + i + ".jpg', now() FROM baymax.document WHERE id = '" + doc + "'");
            ids.add(id);
        }
        return new Fixture(doc, List.copyOf(ids));
    }

    private static UrgencyAssessment assessment(ValueVerdict... verdicts) {
        UrgencyAssessment a = UrgencyAssessment.routine();
        for (ValueVerdict v : verdicts) {
            if (v.level() != Urgency.ROUTINE) {
                a = a.raise(v.level(), v.reason());
            }
        }
        return a.withValues(List.of(verdicts));
    }

    private static ValueVerdict verdict(UUID obs, Kind kind, Urgency level, String reason) {
        return new ValueVerdict(obs, kind, level, kind == Kind.INSIDE || kind == Kind.UNKNOWN ? null : ValueVerdict.Side.HIGH, reason);
    }

    /** CRITICAL stays CRITICAL when a later assessment says INSIDE; the banner stays NOW too. */
    @Test
    void aLowerReassessmentChangesNeitherTheRowNorTheBanner() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "Potassium|6.4|mmol/L");
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(f.doc(), assessment(verdict(f.obs(0), Kind.CRITICAL, Urgency.NOW, "value_critical:potassium|stopgap")), T).block();

            StoredUrgency after = port.raise(f.doc(), assessment(
                    verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:potassium|threshold:x")), T.plusSeconds(5)).block();

            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.values()).singleElement().satisfies(v -> {
                assertThat(v.kind()).isEqualTo(Kind.CRITICAL);
                assertThat(v.reason()).isEqualTo("value_critical:potassium|stopgap");
            });
            // and what a reader gets back later is the stored row, not the last assessment
            assertThat(port.find(f.doc()).block().values()).singleElement()
                    .satisfies(v -> assertThat(v.kind()).isEqualTo(Kind.CRITICAL));
        });
    }

    /** The other direction: INSIDE then UNKNOWN raises the row (UNKNOWN withholds reassurance), and the banner is untouched. */
    @Test
    void insideThenUnknownBecomesUnknown() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "LDL|90|mg/dL");
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(f.doc(), assessment(verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:ldl|stopgap")), T).block();
            StoredUrgency after = port.raise(f.doc(), assessment(
                    verdict(f.obs(0), Kind.UNKNOWN, Urgency.ROUTINE, "value_unknown:ldl|stopgap")), T.plusSeconds(5)).block();
            assertThat(after.values()).singleElement().satisfies(v -> {
                assertThat(v.kind()).isEqualTo(Kind.UNKNOWN);
                assertThat(v.reason()).isEqualTo("value_unknown:ldl|stopgap");
            });
            assertThat(after.level()).isEqualTo(Urgency.ROUTINE);
        });
    }

    /** The pairing lesson (CLAUDE.md): two rows with the same name are two rows. Joined on observation id, never name. */
    @Test
    void twoObservationsWithTheSameNameKeepTheirOwnVerdicts() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "Neutrophil|2.25|10^3/µL", "Neutrophil|41.9|%");
            context.getBean(DocumentUrgencyPort.class).raise(f.doc(), assessment(
                    verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:neutrophil|stopgap"),
                    verdict(f.obs(1), Kind.OUTSIDE, Urgency.THIS_WEEK, "value_outside_range:neutrophil|stopgap")), T).block();
            assertThat(scalar("SELECT verdict FROM baymax.observation WHERE id = '" + f.obs(0) + "'")).isEqualTo("inside");
            assertThat(scalar("SELECT verdict FROM baymax.observation WHERE id = '" + f.obs(1) + "'")).isEqualTo("outside");
        });
    }

    @Test
    void theDatabaseRefusesASoftenedOrMalformedVerdict() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "Potassium|6.4|mmol/L", "Hb|13|g/dL");
            sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='high', "
                    + "verdict_reason='value_critical:potassium|stopgap' WHERE id = '" + f.obs(0) + "'");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', "
                    + "verdict_reason='value_inside:potassium|stopgap' WHERE id = '" + f.obs(0) + "'"))
                    .hasMessageContaining("observation verdict may not soften (critical -> inside)");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict=NULL, verdict_level=NULL, verdict_side=NULL, "
                    + "verdict_reason=NULL WHERE id = '" + f.obs(0) + "'"))
                    .hasMessageContaining("observation verdict may not soften (critical -> NULL)");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='bogus' WHERE id = '" + f.obs(1) + "'"))
                    .hasMessageContaining("observation_verdict_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', "
                    + "verdict_reason='value_inside:hb' WHERE id = '" + f.obs(1) + "'"))
                    .hasMessageContaining("observation_verdict_reason_names_its_rule");
            // the direction that must pass: raising a row directly
            sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='low', "
                    + "verdict_reason='value_critical:hb|stopgap' WHERE id = '" + f.obs(1) + "'");
            assertThat(scalar("SELECT verdict || ' ' || verdict_side FROM baymax.observation WHERE id = '" + f.obs(1) + "'"))
                    .isEqualTo("critical low");
        });
    }

    /** V19's CHECKs, each by name, both directions. */
    @Test
    void everyVerdictConstraintRefusesItsBadWriteAndAllowsItsGoodOne() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "Hb|13|g/dL", "K|6.4|mmol/L", "Na|140|mmol/L");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside' WHERE id = '" + f.obs(0) + "'"))
                    .hasMessageContaining("observation_verdict_complete");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='now', verdict_reason='value_inside:hb|stopgap' WHERE id = '" + f.obs(0) + "'"))
                    .hasMessageContaining("observation_inside_is_routine");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='critical', verdict_level='this_week', verdict_side='high', verdict_reason='value_critical:k|stopgap' WHERE id = '" + f.obs(1) + "'"))
                    .hasMessageContaining("observation_critical_is_now");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='outside', verdict_level='urgent', verdict_reason='value_outside_range:na|stopgap' WHERE id = '" + f.obs(2) + "'"))
                    .hasMessageContaining("observation_verdict_level_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='outside', verdict_level='this_week', verdict_side='up', verdict_reason='value_outside_range:na|stopgap' WHERE id = '" + f.obs(2) + "'"))
                    .hasMessageContaining("observation_verdict_side_check");
            // the good writes
            sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', verdict_reason='value_inside:hb|stopgap' WHERE id = '" + f.obs(0) + "'");
            sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='high', verdict_reason='value_critical:k|stopgap' WHERE id = '" + f.obs(1) + "'");
            sql("UPDATE baymax.observation SET verdict='unknown', verdict_level='routine', verdict_reason='value_unknown:na|threshold:DR-17' WHERE id = '" + f.obs(2) + "'");
            assertThat(scalar("SELECT string_agg(verdict, ',' ORDER BY name) FROM baymax.observation WHERE document_id = '" + f.doc() + "'"))
                    .isEqualTo("inside,critical,unknown");
        });
    }

    /** One transaction: a verdict that cannot be written (no observation id) rolls back the document's urgency too. */
    @Test
    void aVerdictThatCannotBeWrittenRollsBackTheDocumentsUrgency() {
        runner().run(context -> {
            Fixture f = documentWithObservations(context, "K|6.4|mmol/L");
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(f.doc(), assessment(verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:k|stopgap")), T).block();
            UrgencyAssessment broken = assessment(
                    new ValueVerdict(null, Kind.CRITICAL, Urgency.NOW, ValueVerdict.Side.HIGH, "value_critical:k|stopgap"));
            assertThatThrownBy(() -> port.raise(f.doc(), broken, T.plusSeconds(5)).block())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("verdict without observation id");
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + f.doc() + "'")).isEqualTo("routine");
            assertThat(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + f.doc() + "'")).isEqualTo("[]");
        });
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        // the hourly sweep must not assess this class's documents behind its back
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
