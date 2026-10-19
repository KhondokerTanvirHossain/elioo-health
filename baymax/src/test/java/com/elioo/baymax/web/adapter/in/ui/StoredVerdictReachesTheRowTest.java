package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.ValueVerdict;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam between B1 and B2 on real Postgres: a verdict written by the real port (V19) and read back by the real read
 * model (DocumentIntakeUseCase.view, ObservationEntity.toView) draws the row it stands for. Every other page test gets its
 * {@code verdict}/{@code verdict_side} keys from hand-written fixtures; if the stored keys and the keys ValueRows reads
 * drifted apart, every row would silently render "check the paper" and every side chip would vanish.
 */
@Testcontainers
class StoredVerdictReachesTheRowTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void aStoredVerdictAndItsSideReachTheRenderedRow() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            UUID hba1c = observation(doc, "HbA1c", "8.4", "%", "4.0", "6.0", 0);
            // one-sided range: no bar can be drawn, so the chip's side can only have come from the stored verdict_side
            UUID hb = observation(doc, "Hb", "5.0", "g/dL", "12.0", null, 1);
            UUID na = observation(doc, "Na", "140", "mmol/L", "135", "145", 2);
            UrgencyAssessment assessed = new UrgencyAssessment(Urgency.NOW,
                    List.of("value_critical:hb|stopgap", "value_outside_range:hba1c|stopgap")).withValues(List.of(
                    new ValueVerdict(hba1c, ValueVerdict.Kind.OUTSIDE, Urgency.THIS_WEEK, ValueVerdict.Side.HIGH, "value_outside_range:hba1c|stopgap"),
                    new ValueVerdict(hb, ValueVerdict.Kind.CRITICAL, Urgency.NOW, ValueVerdict.Side.LOW, "value_critical:hb|stopgap"),
                    new ValueVerdict(na, ValueVerdict.Kind.INSIDE, Urgency.ROUTINE, null, "value_inside:na|stopgap")));
            context.getBean(DocumentUrgencyPort.class).raise(doc, assessed, T).block();

            DocumentView view = context.getBean(DocumentIntakeUseCase.class).view(doc).block();
            UiCopy copy = new UiCopy();
            Map<UUID, String> rows = new java.util.HashMap<>();
            for (Map<String, Object> v : view.values()) {
                rows.put(UUID.fromString(String.valueOf(v.get("id"))), ValueRows.row(copy, Lang.BN, PageChrome.uuid(view.documentId()), v));
            }
            assertThat(rows).as("rows read back").hasSize(3);
            assertThat(rows.get(hba1c)).startsWith("<a class=\"vrow outside\"")
                    .contains("<span class=\"chip outside\">" + copy.t(Lang.BN, "value.chip.outside.high") + "</span>");
            assertThat(rows.get(hb)).startsWith("<a class=\"vrow critical\"")
                    .contains("<span class=\"chip critical\">" + copy.t(Lang.BN, "value.chip.critical.low") + "</span>")
                    .doesNotContain("<svg");
            assertThat(rows.get(na)).startsWith("<a class=\"vrow inside\"")
                    .contains("<span class=\"chip inside\">" + copy.t(Lang.BN, "value.chip.inside") + "</span>");
        });
    }

    private static UUID doneDocument(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000921", "Tanvir", FamilyAccount.Plan.FREE, T, T)).block();
        PatientProfile patient = records.createPatient(
                new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
        DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
        Document received = documents.create(Document.received(patient.id(), family.id(), 1, T)).block();
        return documents.update(received.withStatus(Document.Status.DONE, null, T)).block().id();
    }

    private static UUID observation(UUID doc, String name, String value, String unit, String low, String high, int i) throws Exception {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO baymax.observation (id, patient_id, document_id, name, canonical_name, value, unit, ref_low, ref_high, crop_key, observed_at) "
                + "SELECT '" + id + "', patient_id, id, '" + name + "', '" + name.toLowerCase(java.util.Locale.ROOT) + "', '" + value + "', '"
                + unit + "', " + literal(low) + ", " + literal(high) + ", 'k/crop-" + i + ".jpg', now() FROM baymax.document WHERE id = '" + doc + "'");
        return id;
    }

    private static String literal(String s) {
        return s == null ? "NULL" : "'" + s + "'";
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        // the hourly sweep must not assess this class's document behind its back
                        "baymax.urgency.sweep-cron=-",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }
}
