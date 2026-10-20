package com.elioo.baymax.wa.adapter.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
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
 * answered (re-dispatch is F9c, Phase 4). Startup counts them, so the gap is visible. Both directions, on real Postgres.
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

            WaInboundStartupReport report = new WaInboundStartupReport(context.getBean(InboundMessagePort.class),
                    Clock.fixed(boot, ZoneOffset.UTC));
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

            WaInboundStartupReport report = new WaInboundStartupReport(context.getBean(InboundMessagePort.class),
                    Clock.fixed(boot, ZoneOffset.UTC));

            assertThat(report.report().block()).isZero();
            assertThat(warnings()).isEmpty();
        });
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }
}
