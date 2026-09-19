package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyMetricsServiceTest {

    private final com.elioo.baymax.nudge.application.port.out.NudgePort nudgePort = org.mockito.Mockito.mock(com.elioo.baymax.nudge.application.port.out.NudgePort.class);

    private static final Instant FROM = Instant.parse("2026-09-05T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-12T00:00:00Z");

    private final com.elioo.baymax.web.application.port.out.AuditPort audit = noAudit();
    private final com.elioo.baymax.outbound.application.port.out.OutboundMessagePort messages = noMessages();

    private static com.elioo.baymax.outbound.application.port.out.OutboundMessagePort noMessages() {
        var m = org.mockito.Mockito.mock(com.elioo.baymax.outbound.application.port.out.OutboundMessagePort.class);
        org.mockito.Mockito.when(m.counts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        return m;
    }

    private static com.elioo.baymax.web.application.port.out.AuditPort noAudit() {
        var a = org.mockito.Mockito.mock(com.elioo.baymax.web.application.port.out.AuditPort.class);
        org.mockito.Mockito.when(a.otpPerNumberPerDay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        org.mockito.Mockito.when(a.viewsPerFamily(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        return a;
    }

    @Test
    void rendersDocumentRowsThenAnEmptyFamiliesBlock() {
        UUID doc = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AiCallLogPort port = mock(AiCallLogPort.class);
        HealthRecordPort records = mock(HealthRecordPort.class);
        UUID family = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(records.familyActivity(any(), any())).thenReturn(Flux.just(
                new FamilyActivity(family, FamilyAccount.Plan.FREE, 1, 2, Instant.parse("2026-09-09T12:00:00Z")),
                new FamilyActivity(UUID.fromString("33333333-3333-3333-3333-333333333333"), FamilyAccount.Plan.FAMILY, 2, 0, null)));
        when(port.extractOutputTokens(any(), any())).thenReturn(Flux.just(900, 1200, 2000, 8192));
        when(nudgePort.counts(any(), any())).thenReturn(Flux.just(new com.elioo.baymax.nudge.domain.NudgeCount(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), com.elioo.baymax.nudge.domain.NudgeRule.TREND, com.elioo.baymax.nudge.domain.NudgeStatus.GATED, null, 2), new com.elioo.baymax.nudge.domain.NudgeCount(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), com.elioo.baymax.nudge.domain.NudgeRule.TREND, com.elioo.baymax.nudge.domain.NudgeStatus.DROPPED, "weekly_cap", 1)));
        when(port.perDocument(any(), any())).thenReturn(Flux.just(
                new DocumentAiCost(doc, 3, 1, 4200, 900, new BigDecimal("0.00117000"),
                        "gcp/vision-document-text-detection|groq/openai/gpt-oss-120b", 0.905, 2,
                        Instant.parse("2026-09-06T10:00:00Z"), Instant.parse("2026-09-06T10:00:09Z")),
                new DocumentAiCost(null, 1, 1, 50, 10, null, "groq/llama", null, 0,
                        Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"))));

        StepVerifier.create(new WeeklyMetricsService(port, records, audit, messages, nudgePort).weeklyCsv(FROM, TO))
                .assertNext(csv -> {
                    List<String> lines = csv.lines().toList();
                    assertThat(lines.get(0)).isEqualTo("# documents from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)");
                    assertThat(lines.get(1)).isEqualTo(WeeklyMetricsService.DOCUMENT_HEADER);
                    // unverified_items sits beside the cost: a cheap document with items we could not
                    // locate is not the same as a cheap document that was read cleanly
                    assertThat(lines.get(2)).isEqualTo("11111111-1111-1111-1111-111111111111,3,1,4200,900,0.00117,"
                            + "gcp/vision-document-text-detection|groq/openai/gpt-oss-120b,0.9050,2,"
                            + "2026-09-06T10:00:00Z,2026-09-06T10:00:09Z");
                    assertThat(lines.get(3)).isEqualTo(",1,1,50,10,,groq/llama,,0,2026-09-07T00:00:00Z,2026-09-07T00:00:00Z");
                    assertThat(lines.get(4)).isEmpty();
                    assertThat(lines.get(5)).startsWith("# families from=");
                    assertThat(lines.get(6)).isEqualTo(WeeklyMetricsService.FAMILY_HEADER);
                    assertThat(lines.get(7)).isEqualTo("22222222-2222-2222-2222-222222222222,free,1,2,0,0,2");
                    assertThat(lines.get(8)).isEqualTo("33333333-3333-3333-3333-333333333333,family,2,0,0,0,");
                    // BMX-5 appended two blocks: auth (per hashed number per day) and views (per family)
                    assertThat(lines.get(10)).startsWith("# auth from=");
                    assertThat(lines.get(11)).isEqualTo(WeeklyMetricsService.AUTH_HEADER);
                    assertThat(lines.get(13)).startsWith("# views from=");
                    assertThat(lines.get(14)).isEqualTo(WeeklyMetricsService.VIEWS_HEADER);
                    assertThat(lines.get(16)).startsWith("# messages from=");
                    assertThat(lines.get(17)).isEqualTo(WeeklyMetricsService.MESSAGES_HEADER);
                    assertThat(lines.get(19)).startsWith("# extraction from=");
                    assertThat(lines.get(20)).isEqualTo(WeeklyMetricsService.EXTRACTION_HEADER);
                    assertThat(lines.get(21)).isEqualTo("4,8192,8192,1200");   // max, p95, median of 900/1200/2000/8192
                    assertThat(lines.get(23)).startsWith("# nudges from=");
                    assertThat(lines.get(24)).isEqualTo(WeeklyMetricsService.NUDGES_HEADER);
                    assertThat(lines.get(25)).isEqualTo("00000000-0000-0000-0000-000000000001,trend,0,2,0,0,1,weekly_cap:1,0,n/a");   // sent,gated,held,deferred,dropped,...
                    assertThat(lines).hasSize(26);
                })
                .verifyComplete();
    }

    @Test
    void emptyWindowStillHasAllSevenHeaders() {
        AiCallLogPort port = mock(AiCallLogPort.class);
        HealthRecordPort records = mock(HealthRecordPort.class);
        when(port.perDocument(any(), any())).thenReturn(Flux.empty());
        when(port.extractOutputTokens(any(), any())).thenReturn(Flux.empty());
        when(nudgePort.counts(any(), any())).thenReturn(Flux.empty());
        when(records.familyActivity(any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(new WeeklyMetricsService(port, records, audit, messages, nudgePort).weeklyCsv(FROM, TO))
                .assertNext(csv -> assertThat(csv.lines().toList())
                        .containsExactly(
                                "# documents from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.DOCUMENT_HEADER,
                                "",
                                "# families from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.FAMILY_HEADER,
                                "",
                                "# auth from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.AUTH_HEADER,
                                "",
                                "# views from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.VIEWS_HEADER,
                                "",
                                "# messages from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.MESSAGES_HEADER,
                                "",
                                "# extraction from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.EXTRACTION_HEADER,
                                "",
                                "# nudges from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.NUDGES_HEADER))
                .verifyComplete();
    }

    @Test
    void csvEscapingQuotesCommasAndQuotes() {
        assertThat(WeeklyMetricsService.csvEscape("plain")).isEqualTo("plain");
        assertThat(WeeklyMetricsService.csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(WeeklyMetricsService.csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}
