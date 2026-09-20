package com.elioo.baymax.outbound;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
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
 * V13 on real Postgres: {@code sent_at} means "the provider accepted it" and nothing else sets it.
 *
 * <p>Asserted on the column itself rather than on the returned record — the defect being prevented is a row
 * that claims delivery when none happened, and that claim lives in the database. A test that only checked the
 * in-memory object would pass over a column written wrongly, which is the proxy-assertion failure this
 * repository keeps finding.
 */
@Testcontainers
class DeliveryOutcomeAcceptanceTest {

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

    @Test
    void aFailedSendLeavesSentAtNullAndRecordsWhy() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-09-21T09:00:00Z");

            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000701", "Rahim", FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage stored = messages.save(new OutboundMessage(null, family.id(), null, null,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "শরীর ভালো আছে",
                    OutboundMessage.GateStatus.PENDING, null, null, null, null, now)).block();

            // the provider refused: the 24-hour window had closed
            messages.recordDelivery(stored.id(), DeliveryOutcome.failed(DeliveryOutcome.WINDOW_EXPIRED), now).block();

            assertThat(column(stored.id(), "sent_at"))
                    .as("a message the provider refused was never received, so sent_at must stay NULL").isNull();
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("failed");
            assertThat(column(stored.id(), "delivery_error")).isEqualTo("window_expired");
            assertThat(column(stored.id(), "provider_message_id")).isNull();
        });
    }

    @Test
    void anAcceptedSendSetsSentAtAndKeepsTheProviderId() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-09-21T09:05:00Z");

            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000702", "Karim", FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage stored = messages.save(new OutboundMessage(null, family.id(), null, null,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "শরীর ভালো আছে",
                    OutboundMessage.GateStatus.PENDING, null, null, null, null, now)).block();

            messages.recordDelivery(stored.id(), DeliveryOutcome.sent("wamid.ACCEPTED1"), now).block();

            assertThat(column(stored.id(), "sent_at")).as("the provider accepted it").isNotNull();
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("sent");
            assertThat(column(stored.id(), "provider_message_id")).isEqualTo("wamid.ACCEPTED1");
            assertThat(column(stored.id(), "delivery_error")).isNull();
        });
    }

    /** A later failure must clear a previously-set sent_at rather than leave a stale claim of delivery. */
    @Test
    void aFailureAfterASendClearsSentAtRatherThanLeavingAStaleClaim() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-09-21T09:10:00Z");

            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000703", "Salma", FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage stored = messages.save(new OutboundMessage(null, family.id(), null, null,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "শরীর ভালো আছে",
                    OutboundMessage.GateStatus.PENDING, null, null, null, null, now)).block();

            messages.recordDelivery(stored.id(), DeliveryOutcome.sent("wamid.FIRST"), now).block();
            assertThat(column(stored.id(), "sent_at")).isNotNull();

            messages.recordDelivery(stored.id(), DeliveryOutcome.failed(DeliveryOutcome.TOKEN_INVALID), now).block();
            assertThat(column(stored.id(), "sent_at"))
                    .as("sent_at must not survive a subsequent failure").isNull();
            assertThat(column(stored.id(), "delivery_error")).isEqualTo("token_invalid");
        });
    }

    /**
     * The defect the first production run exposed: V13 was wired into approve() only, so a RELEASED message —
     * one the gate never parked — went out over WhatsApp while its row said delivery_status NULL, no wamid, and
     * sent_at stamped at save time. The row claimed a delivery it had not observed. Asserted on the columns for
     * the released path specifically, because that is the path that was missed.
     */
    @Test
    void aReleasedMessageRecordsItsProviderOutcomeRatherThanClaimingDeliveryAtSaveTime() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-09-21T10:00:00Z");

            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000704", "Nasrin", FamilyAccount.Plan.FREE, now, now)).block();
            // a released row as ExplanationService.release now saves it: sent_at NULL until a provider answers
            OutboundMessage released = messages.save(new OutboundMessage(null, family.id(), null, null,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "শরীর ভালো আছে",
                    OutboundMessage.GateStatus.RELEASED, null, null, null, null, now)).block();

            assertThat(column(released.id(), "sent_at"))
                    .as("a released row must not claim delivery before the provider has answered").isNull();

            messages.recordDelivery(released.id(), DeliveryOutcome.sent("wamid.RELEASED1"), now).block();
            assertThat(column(released.id(), "provider_message_id")).isEqualTo("wamid.RELEASED1");
            assertThat(column(released.id(), "delivery_status")).isEqualTo("sent");
            assertThat(column(released.id(), "sent_at")).isNotNull();
        });
    }

    /**
     * V14: the invariant lives at the database, so it holds for writers that do not exist yet. V13 was scoped
     * to approve() and both release paths kept the defect — a rule enforced per call site is only as good as
     * the last person who remembered it.
     */
    @Test
    void theDatabaseItselfRefusesARowThatClaimsDeliveryItDidNotGet() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-09-21T11:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000705", "Jamal", FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage stored = messages.save(new OutboundMessage(null, family.id(), null, null,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "শরীর ভালো আছে",
                    OutboundMessage.GateStatus.RELEASED, null, null, null, null, now)).block();

            // raw SQL, bypassing every service: sent_at alongside a failed delivery must not be storable
            assertThatThrownBy(() -> execute("UPDATE baymax.outbound_message SET delivery_status = 'failed', "
                    + "delivery_error = 'window_expired', sent_at = now() WHERE id = '" + stored.id() + "'"))
                    .hasMessageContaining("outbound_message_sent_at_requires_acceptance");

            // a sent delivery without the provider's id is not evidence of anything
            assertThatThrownBy(() -> execute("UPDATE baymax.outbound_message SET delivery_status = 'sent', "
                    + "provider_message_id = NULL, sent_at = now() WHERE id = '" + stored.id() + "'"))
                    .hasMessageContaining("outbound_message_sent_status_has_provider_id");

            // a failure always says why
            assertThatThrownBy(() -> execute("UPDATE baymax.outbound_message SET delivery_status = 'failed', "
                    + "delivery_error = NULL WHERE id = '" + stored.id() + "'"))
                    .hasMessageContaining("outbound_message_failed_status_has_reason");

            // the honest shapes are accepted
            execute("UPDATE baymax.outbound_message SET delivery_status = 'sent', provider_message_id = 'wamid.OK', "
                    + "delivery_error = NULL, sent_at = now() WHERE id = '" + stored.id() + "'");
            assertThat(column(stored.id(), "sent_at")).isNotNull();
        });
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
