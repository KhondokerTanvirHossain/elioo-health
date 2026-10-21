package com.elioo.baymax.aicall.adapter.out.persistence;

import com.elioo.baymax.aicall.application.port.in.WeeklyMetricsUseCase;
import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance for BMX-1 against a real Postgres: rows land in {@code baymax.ai_call_log} with the right
 * columns, and the weekly per-document export equals {@code SUM(...)} over that table.
 */
@Testcontainers
class AiCallLogPersistenceAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");
    /** Far outside the export window the first test reads, so the two tests share the container without meeting. */
    private static final Instant OUTSIDE = T0.minusSeconds(400L * 86400);

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class,
                        R2dbcDataAutoConfiguration.class,
                        LlmAutoConfiguration.class,
                        BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true",
                        "llm.provider=groq",
                        "llm.groq.api-key=test-key",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + jdbcUrl(),
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    @Test
    void savedRowsAreQueryableAndTheWeeklyExportMatchesSqlSums() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        runner().run(context -> {
            AiCallLogPort port = context.getBean(AiCallLogPort.class);
            // the papers exist: a call for a paper that no longer exists loses its id (see the test below)
            papers("+8801700000497", docA, docB);
            int base = countRows();

            AiCallRecord ocr = port.save(new AiCallRecord(null, docA, AiCallPurpose.OCR, "gcp",
                    "vision-document-text-detection", 0, 0, new BigDecimal("0.0015"), 1200L, 0.9, T0)).block();
            port.save(new AiCallRecord(null, docA, AiCallPurpose.EXTRACT, "groq", "openai/gpt-oss-120b",
                    4000, 800, new BigDecimal("0.00108"), 2500L, 0.8, T0.plusSeconds(3))).block();
            port.save(new AiCallRecord(null, docA, AiCallPurpose.EXPLAIN, "groq", "llama-unpriced",
                    100, 50, null, 300L, null, T0.plusSeconds(6))).block();
            port.save(new AiCallRecord(null, docB, AiCallPurpose.EXTRACT, "anthropic", "claude-opus-5",
                    3000, 500, new BigDecimal("0.0275"), 4000L, 0.95, T0.plusSeconds(60))).block();
            port.save(new AiCallRecord(null, null, AiCallPurpose.CHAT, "groq", "openai/gpt-oss-120b",
                    200, 100, new BigDecimal("0.00009"), 200L, null, T0.plusSeconds(120))).block();
            // outside the window: must not be counted
            port.save(new AiCallRecord(null, docA, AiCallPurpose.CHAT, "groq", "openai/gpt-oss-120b",
                    999, 999, new BigDecimal("1"), 1L, null, T0.plusSeconds(30L * 86400))).block();

            assertThat(ocr.id()).isNotNull();
            assertThat(countRows()).isEqualTo(base + 6);
            assertThat(sqlScalar("select purpose from baymax.ai_call_log where id = '" + ocr.id() + "'")).isEqualTo("ocr");

            List<DocumentAiCost> rows = port.perDocument(T0.minusSeconds(1), T0.plusSeconds(3600)).collectList().block();
            assertThat(rows).hasSize(3);

            DocumentAiCost a = rows.stream().filter(r -> docA.equals(r.documentId())).findFirst().orElseThrow();
            assertThat(a.calls()).isEqualTo(3);
            assertThat(a.unpricedCalls()).isEqualTo(1);
            assertThat(a.inputTokens()).isEqualTo(4100);
            assertThat(a.outputTokens()).isEqualTo(850);
            assertThat(a.costUsd()).isEqualByComparingTo(new BigDecimal(sqlScalar(
                    "select sum(cost_usd) from baymax.ai_call_log where document_id = '" + docA + "' and created_at < '2026-09-06T11:00:00Z'")));
            assertThat(a.costUsd()).isEqualByComparingTo("0.00258");
            assertThat(a.models()).isEqualTo("gcp/vision-document-text-detection|groq/llama-unpriced|groq/openai/gpt-oss-120b");
            assertThat(a.avgConfidence()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(a.firstCallAt()).isEqualTo(T0);
            assertThat(a.lastCallAt()).isEqualTo(T0.plusSeconds(6));

            DocumentAiCost untied = rows.stream().filter(r -> r.documentId() == null).findFirst().orElseThrow();
            assertThat(untied.calls()).isEqualTo(1);
            assertThat(untied.costUsd()).isEqualByComparingTo("0.00009");
            assertThat(rows.get(rows.size() - 1).documentId()).as("untied rows sort last").isNull();

            String csv = context.getBean(WeeklyMetricsUseCase.class)
                    .weeklyCsv(T0.minusSeconds(1), T0.plusSeconds(3600)).block();
            assertThat(csv).contains(docA + ",3,1,4100,850,0.00258,");
            assertThat(csv).contains(docB + ",1,0,3000,500,0.0275,anthropic/claude-opus-5,0.9500,");
            // the out-of-window row carries 999 tokens; match it as a CSV field, because the export now holds
            // several random UUIDs (documents, and families in the # views block) and one in fifty contains "999"
            assertThat(csv).doesNotContain(",999,");
        });
    }

    /**
     * A paper deleted while its call was running (an EXPLAIN takes seconds) must not be named by the call's row after
     * the delete: the row keeps its numbers, as the delete keeps them for earlier calls, and loses the id. The other
     * direction: a call for a paper that still exists keeps it.
     */
    @Test
    void aCallForAPaperThatNoLongerExistsKeepsItsNumbersAndLosesTheId() {
        UUID gone = UUID.randomUUID();
        UUID there = UUID.randomUUID();
        runner().run(context -> {
            AiCallLogPort port = context.getBean(AiCallLogPort.class);
            papers("+8801700000498", there);

            AiCallRecord late = port.save(new AiCallRecord(null, gone, AiCallPurpose.EXPLAIN, "groq", "openai/gpt-oss-120b",
                    300, 120, new BigDecimal("0.0002"), 900L, null, OUTSIDE)).block();
            AiCallRecord kept = port.save(new AiCallRecord(null, there, AiCallPurpose.EXPLAIN, "groq", "openai/gpt-oss-120b",
                    300, 120, new BigDecimal("0.0002"), 900L, null, OUTSIDE)).block();

            assertThat(sqlScalar("select count(*) from baymax.ai_call_log where document_id = '" + gone + "'"))
                    .as("rows naming the paper that no longer exists").isEqualTo("0");
            assertThat(sqlScalar("select input_tokens || '/' || cost_usd from baymax.ai_call_log where id = '" + late.id() + "'"))
                    .as("the late call's numbers, kept").isEqualTo("300/0.00020000");
            assertThat(late.documentId()).as("the late call as saved").isNull();
            assertThat(sqlScalar("select document_id from baymax.ai_call_log where id = '" + kept.id() + "'"))
                    .as("a call for a paper that still exists keeps its id").isEqualTo(there.toString());
        });
    }

    /** One family and patient owning these papers (fictional number), so a call's document_id names a real row. */
    private static void papers(String number, UUID... documents) throws Exception {
        UUID family = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("insert into baymax.family_account (id, whatsapp_number, owner_name, terms_accepted_at) values ('"
                    + family + "', '" + number + "', 'Rahim', now())");
            s.execute("insert into baymax.patient_profile (id, family_id, name, age, sex, proxy_consent_at) values ('"
                    + patient + "', '" + family + "', 'Ma', 64, 'female', now())");
            for (UUID d : documents) {
                s.execute("insert into baymax.document (id, patient_id, family_id) values ('" + d + "', '" + patient + "', '" + family + "')");
            }
        }
    }

    private static int countRows() throws Exception {
        return Integer.parseInt(sqlScalar("select count(*) from baymax.ai_call_log"));
    }

    private static String sqlScalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** The container speaks no TLS; skip the driver's SSL attempt, which occasionally fails mid-handshake. */
    private static String jdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&sslmode=disable";
    }
}
