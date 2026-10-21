package com.elioo.baymax.wa.adapter.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import com.elioo.baymax.wa.application.service.InboundRecoveryService;
import com.elioo.baymax.wa.application.service.WaInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F9a leaves one gap open: a message claimed before the 200 and lost to a restart before its handling finished is never
 * answered unless Meta re-delivers it (re-dispatch is F9c, Phase 4). Startup counts them, so the gap is visible, and
 * closes each one as CUT_OFF so the next boot counts only what IT cut off. Both directions, on real Postgres, and over
 * successive boots: a count that never closes its rows is a running total, not "what this restart cut off".
 */
@Testcontainers
class WaInboundStartupReportTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(WaInboundStartupReport.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private List<String> warnings() {
        return logged.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    /** A startup as the app wires it: the instance started at {@code boot}, the report reads through the use case. */
    private static WaInboundStartupReport bootAt(org.springframework.context.ApplicationContext context, Instant boot) {
        Clock clock = Clock.fixed(boot, ZoneOffset.UTC);
        return new WaInboundStartupReport(new InboundRecoveryService(context.getBean(InboundMessagePort.class),
                new WaInstance(clock), clock));
    }

    @Test
    void messagesReceivedAndNeverAnsweredBeforeTheRestartAreCounted() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            Instant boot = Instant.parse("2026-10-17T08:00:00Z");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(1) + "','text','2026-10-17T07:00:00Z')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(2) + "','image','2026-10-17T07:00:00Z')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at, handled_at, outcome) values ('"
                    + key(3) + "','text','2026-10-17T07:00:00Z','2026-10-17T07:00:04Z','REPLIED')");
            // arrived after this instance started: in flight, not cut off
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(4) + "','text','2026-10-17T08:00:01Z')");

            WaInboundStartupReport report = bootAt(context, boot);
            report.onReady();

            await().atMost(Duration.ofSeconds(10)).until(() -> !warnings().isEmpty());
            assertThat(warnings()).containsExactly(
                    "[baymax] 2 WhatsApp message(s) were received but never answered before this restart "
                            + "(re-dispatch is F9c; each one is a family that heard nothing)");
        });
    }

    @Test
    void whenEveryMessageWasAnsweredNothingIsWarned() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            Instant boot = Instant.parse("2026-10-10T08:00:00Z");
            sql("insert into baymax.inbound_message (provider_key, type, received_at, handled_at, outcome) values ('"
                    + key(11) + "','text','2026-10-10T07:00:00Z','2026-10-10T07:00:04Z','REPLIED')");

            WaInboundStartupReport report = bootAt(context, boot);

            assertThat(report.report().block()).isZero();
            assertThat(warnings()).isEmpty();
        });
    }

    /**
     * Two deploys over the same rows. The first reports what it cut off; the second must not report those again, and a
     * message cut off between the two boots is counted exactly once, by the second. A third boot with nothing new is
     * silent. The closed rows stay visible by their outcome.
     */
    @Test
    void eachCutOffMessageIsReportedByOneBootOnly() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(21) + "','text','2026-09-01T07:00:00Z')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(22) + "','image','2026-09-01T07:30:00Z')");

            assertThat(bootAt(context, Instant.parse("2026-09-01T08:00:00Z")).report().block()).as("first boot").isEqualTo(2L);

            // cut off by the SECOND restart: received while the first boot's instance ran
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(23) + "','text','2026-09-01T09:00:00Z')");

            assertThat(bootAt(context, Instant.parse("2026-09-01T10:00:00Z")).report().block())
                    .as("second boot: only what the second restart cut off").isEqualTo(1L);
            assertThat(bootAt(context, Instant.parse("2026-09-01T11:00:00Z")).report().block())
                    .as("third boot, nothing new").isZero();

            assertThat(warnings()).containsExactly(
                    "[baymax] 2 WhatsApp message(s) were received but never answered before this restart "
                            + "(re-dispatch is F9c; each one is a family that heard nothing)",
                    "[baymax] 1 WhatsApp message(s) were received but never answered before this restart "
                            + "(re-dispatch is F9c; each one is a family that heard nothing)");
            assertThat(scalar("select string_agg(outcome || '/' || (handled_at is not null), ',' order by provider_key) "
                    + "from baymax.inbound_message where provider_key in ('" + key(21) + "','" + key(22) + "','" + key(23) + "')"))
                    .as("closed, visible by outcome").isEqualTo("CUT_OFF/true,CUT_OFF/true,CUT_OFF/true");
        });
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }
}
