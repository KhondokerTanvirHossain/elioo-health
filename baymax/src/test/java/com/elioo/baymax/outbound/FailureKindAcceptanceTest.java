package com.elioo.baymax.outbound;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
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
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A document that ends FAILED sends the family a notice (Phase 0, PR 9). The notice is an outbound_message of its own
 * kind, so it passes the gate stage and records its provider outcome like every other (V13/V14). On real Postgres,
 * because the kind is a CHECK constraint (CLAUDE.md: a constrained path is never only mocked).
 */
@Testcontainers
class FailureKindAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    private static OutboundMessage row(UUID family, OutboundMessage.Kind kind, Instant at) {
        return new OutboundMessage(null, family, null, null, kind, Urgency.ROUTINE, List.of(), "x",
                OutboundMessage.GateStatus.RELEASED, null, null, null, null, at);
    }

    @Test
    void aFailureNoticeIsStorableAndReadsBackAsItsOwnKind() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-10-17T09:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000801", "Rahim",
                    FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage saved = messages.save(row(family.id(), OutboundMessage.Kind.FAILURE, now)).block();
            assertThat(column(saved.id(), "kind")).isEqualTo("failure");
            assertThat(messages.find(saved.id()).block().kind()).isEqualTo(OutboundMessage.Kind.FAILURE);
        });
    }

    /** The other direction: widening the CHECK must not have dropped it. */
    @Test
    void anUnknownKindIsStillRefusedByTheDatabase() {
        runner().run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-10-17T09:05:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000802", "Karim",
                    FamilyAccount.Plan.FREE, now, now)).block();
            assertThatThrownBy(() -> execute("INSERT INTO baymax.outbound_message (family_id, kind, urgency, gate_status) "
                    + "VALUES ('" + family.id() + "', 'bogus', 'routine', 'released')"))
                    .hasMessageContaining("outbound_message_kind_check");
        });
    }

    /**
     * A failure is our fault, not the family's success, so it must not reset the retake escalation
     * (ExplanationService.retake). An explanation still does reset it.
     */
    @Test
    void aFailureNoticeDoesNotResetTheRetakeEscalationButAnExplanationDoes() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-17T10:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000803", "Salma",
                    FamilyAccount.Plan.FREE, t, t)).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t)).block();
            messages.save(row(family.id(), OutboundMessage.Kind.FAILURE, t.plusSeconds(60))).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t.plusSeconds(120))).block();
            assertThat(messages.consecutiveRetakes(family.id()).block())
                    .as("a failure notice between two retakes must not reset the count").isEqualTo(2L);

            messages.save(row(family.id(), OutboundMessage.Kind.EXPLANATION, t.plusSeconds(180))).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t.plusSeconds(240))).block();
            assertThat(messages.consecutiveRetakes(family.id()).block()).isEqualTo(1L);
        });
    }

    /** failure.document says "this will not be counted": asserted through the check an upload actually meets. */
    @Test
    void aFamilyOneBelowTheCapWithAFailedDocumentMayStillUploadAndOneMoreDoneBlocksIt() {
        runner().withPropertyValues("baymax.free.max-docs-per-month=2").run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            FreeTierUseCase freeTier = context.getBean(FreeTierUseCase.class);
            Instant now = Instant.now();   // the free tier counts the current UTC month
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000804", "Nasrin",
                    FamilyAccount.Plan.FREE, now, now)).block();
            PatientProfile ma = records.createPatient(new PatientProfile(null, family.id(), "মা", 70,
                    PatientProfile.Sex.FEMALE, List.of(), now, now)).block();
            finished(documents, ma, Document.Status.DONE, now);
            finished(documents, ma, Document.Status.FAILED, now);

            StepVerifier.create(freeTier.checkCanUploadDocument(family.id())).verifyComplete();   // 1 DONE + 1 FAILED, cap 2

            finished(documents, ma, Document.Status.DONE, now);
            StepVerifier.create(freeTier.checkCanUploadDocument(family.id()))
                    .expectError(FreeTierExceededException.class).verify();                    // 2 DONE: at the cap
        });
    }

    private static void finished(DocumentRecordPort documents, PatientProfile p, Document.Status status, Instant at) {
        Document d = documents.create(Document.received(p.id(), p.familyId(), 1, at)).block();
        documents.update(d.withStatus(status, status == Document.Status.FAILED ? "llm_error" : null, at)).block();
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String column(UUID id, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + name + " FROM baymax.outbound_message WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
