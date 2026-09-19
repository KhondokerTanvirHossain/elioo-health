package com.elioo.baymax.nudge.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-22, on real Postgres. The unit tests mock {@link NudgePort} with a {@code HashSet} ledger, and a duplicate
 * {@code add} on a set is a silent no-op where the database raises a unique-constraint violation — so the
 * defer → consume → re-insert sequence was green in the mocked test and 500'd in production, with the deferral
 * already consumed and no replacement row written. A mock that cannot fail the way production fails is not a
 * test of this path; this one runs the real schema.
 */
@Testcontainers
class PostgresNudgeAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TRIGGER = "follow_up:5e9edf6d-0a2a-4913-8675-9d07a4000001";

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

    /**
     * The production sequence that broke: a date-bound nudge is DEFERRED by a tie (DR-20), the next evaluation
     * consumes the deferral (retiring the row as dropped, which DR-19's audit trail requires), and then inserts
     * the retry for the same (patient, rule, trigger). Under V10's blanket UNIQUE that insert collided with the
     * row it had just retired; under DR-22's partial index over live rows it succeeds and the nudge is sent.
     */
    @Test
    void aConsumedDeferralIsRetiredAndTheRetryInsertsAndSends() {
        runner().run(context -> {
            NudgePort nudges = context.getBean(NudgePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-09-19T08:00:00Z");

            FamilyAccount family = records.createFamily(
                    new FamilyAccount(null, "+8801700000501", "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
            PatientProfile patient = records.createPatient(
                    new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of("diabetes"), t, t)).block();

            // day one: the follow-up loses the tie to the trend and is DEFERRED, not dropped (DR-20)
            Nudge deferred = nudges.save(row(family, patient, NudgeStatus.DEFERRED, "superseded_by_trend", t)).block();
            assertThat(deferred.id()).isNotNull();
            assertThat(nudges.exists(patient.id(), NudgeRule.FOLLOW_UP_DUE, TRIGGER).block()).isTrue();

            // day two: the deferral is consumed — the row is retired as dropped and KEPT (DR-19's audit trail)
            Instant nextDay = t.plusSeconds(86_400);
            assertThat(nudges.consumeDeferred(patient.id(), NudgeRule.FOLLOW_UP_DUE, TRIGGER, nextDay).block()).isTrue();

            // ...and the retry inserts cleanly for the same (patient, rule, trigger). This is the line that threw
            // "duplicate key value violates unique constraint" in production before DR-22.
            Nudge retry = nudges.save(row(family, patient, NudgeStatus.GATED, null, nextDay)).block();
            assertThat(retry.id()).isNotNull().isNotEqualTo(deferred.id());

            // both rows survive: one retired, one live — the history DR-19 asked for. Checked in SQL so the
            // assertion is about what the table holds, not about what a port method chooses to return.
            assertThat(scalar("select count(*) from baymax.nudge where patient_id = '" + patient.id() + "'"))
                    .as("the retired row is kept beside the retry").isEqualTo("2");
            assertThat(scalar("select count(*) from baymax.nudge where patient_id = '" + patient.id()
                    + "' and status = 'dropped' and drop_reason = 'superseded_by_retry'")).isEqualTo("1");
            assertThat(scalar("select count(*) from baymax.nudge where patient_id = '" + patient.id()
                    + "' and status not in ('dropped','failed')"))
                    .as("exactly one live nudge per trigger (DR-22)").isEqualTo("1");
            assertThat(nudges.latest(patient.id(), NudgeRule.FOLLOW_UP_DUE).block().status()).isEqualTo(NudgeStatus.GATED);

            // consuming again finds nothing: a deferral is consumed once
            assertThat(nudges.consumeDeferred(patient.id(), NudgeRule.FOLLOW_UP_DUE, TRIGGER, nextDay).block()).isFalse();
        });
    }

    private static String scalar(String query) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Nudge row(FamilyAccount family, PatientProfile patient, NudgeStatus status, String reason, Instant at) {
        return new Nudge(null, family.id(), patient.id(), NudgeRule.FOLLOW_UP_DUE, TRIGGER, NudgeUrgency.THIS_WEEK,
                status, reason, Map.of("patient", "Ma"), null, null, at, null);
    }
}
