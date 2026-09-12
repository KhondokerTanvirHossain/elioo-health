package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
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

    private static final Instant FROM = Instant.parse("2026-09-05T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void rendersDocumentRowsThenAnEmptyFamiliesBlock() {
        UUID doc = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AiCallLogPort port = mock(AiCallLogPort.class);
        when(port.perDocument(any(), any())).thenReturn(Flux.just(
                new DocumentAiCost(doc, 3, 1, 4200, 900, new BigDecimal("0.00117000"),
                        "gcp/vision-document-text-detection|groq/openai/gpt-oss-120b", 0.905,
                        Instant.parse("2026-09-06T10:00:00Z"), Instant.parse("2026-09-06T10:00:09Z")),
                new DocumentAiCost(null, 1, 1, 50, 10, null, "groq/llama", null,
                        Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"))));

        StepVerifier.create(new WeeklyMetricsService(port).weeklyCsv(FROM, TO))
                .assertNext(csv -> {
                    List<String> lines = csv.lines().toList();
                    assertThat(lines.get(0)).isEqualTo("# documents from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)");
                    assertThat(lines.get(1)).isEqualTo(WeeklyMetricsService.DOCUMENT_HEADER);
                    assertThat(lines.get(2)).isEqualTo("11111111-1111-1111-1111-111111111111,3,1,4200,900,0.00117,"
                            + "gcp/vision-document-text-detection|groq/openai/gpt-oss-120b,0.9050,"
                            + "2026-09-06T10:00:00Z,2026-09-06T10:00:09Z");
                    assertThat(lines.get(3)).isEqualTo(",1,1,50,10,,groq/llama,,2026-09-07T00:00:00Z,2026-09-07T00:00:00Z");
                    assertThat(lines.get(4)).isEmpty();
                    assertThat(lines.get(5)).startsWith("# families from=");
                    assertThat(lines.get(6)).isEqualTo(WeeklyMetricsService.FAMILY_HEADER);
                    assertThat(lines).hasSize(7);
                })
                .verifyComplete();
    }

    @Test
    void emptyWindowStillHasBothHeaders() {
        AiCallLogPort port = mock(AiCallLogPort.class);
        when(port.perDocument(any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(new WeeklyMetricsService(port).weeklyCsv(FROM, TO))
                .assertNext(csv -> assertThat(csv.lines().toList())
                        .containsExactly(
                                "# documents from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.DOCUMENT_HEADER,
                                "",
                                "# families from=2026-09-05T00:00:00Z to=2026-09-12T00:00:00Z (to exclusive)",
                                WeeklyMetricsService.FAMILY_HEADER))
                .verifyComplete();
    }

    @Test
    void csvEscapingQuotesCommasAndQuotes() {
        assertThat(WeeklyMetricsService.csvEscape("plain")).isEqualTo("plain");
        assertThat(WeeklyMetricsService.csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(WeeklyMetricsService.csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}
