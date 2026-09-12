package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.in.WeeklyMetricsUseCase;
import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders the weekly pilot log as one CSV with two blocks.
 *
 * <p><b>documents</b>: one row per document (and one with an empty id for untied calls) whose numbers are
 * exactly {@code SUM}/{@code AVG} over {@code ai_call_log} in the window. A non-zero {@code unpriced_calls}
 * means {@code cost_usd} is a lower bound.</p>
 *
 * <p><b>families</b>: the column set is fixed here so the export shape does not change later, but the
 * rows need the family, document and outbound-message tables, which arrive with their own tickets.
 * Until then the block is header-only.</p>
 */
@Service
@RequiredArgsConstructor
public class WeeklyMetricsService implements WeeklyMetricsUseCase {

    static final String DOCUMENT_HEADER = "document_id,calls,unpriced_calls,input_tokens,output_tokens,"
            + "cost_usd,models,avg_confidence,first_call_at,last_call_at";
    static final String FAMILY_HEADER = "family_id,documents,nudges_sent,nudges_answered,days_since_last_document";

    private final AiCallLogPort callLog;

    @Override
    public Mono<String> weeklyCsv(Instant from, Instant to) {
        return callLog.perDocument(from, to).collectList().map(rows -> render(rows, from, to));
    }

    static String render(List<DocumentAiCost> rows, Instant from, Instant to) {
        String window = "from=" + from + " to=" + to + " (to exclusive)";
        StringBuilder csv = new StringBuilder();
        csv.append("# documents ").append(window).append('\n').append(DOCUMENT_HEADER).append('\n');
        for (DocumentAiCost row : rows) {
            csv.append(line(row)).append('\n');
        }
        csv.append('\n').append("# families ").append(window).append('\n').append(FAMILY_HEADER).append('\n');
        return csv.toString();
    }

    private static String line(DocumentAiCost r) {
        return Stream.of(
                        r.documentId() == null ? "" : r.documentId().toString(),
                        Long.toString(r.calls()),
                        Long.toString(r.unpricedCalls()),
                        Long.toString(r.inputTokens()),
                        Long.toString(r.outputTokens()),
                        r.costUsd() == null ? "" : r.costUsd().stripTrailingZeros().toPlainString(),
                        r.models() == null ? "" : r.models(),
                        r.avgConfidence() == null ? "" : String.format(java.util.Locale.ROOT, "%.4f", r.avgConfidence()),
                        r.firstCallAt() == null ? "" : r.firstCallAt().toString(),
                        r.lastCallAt() == null ? "" : r.lastCallAt().toString())
                .map(WeeklyMetricsService::csvEscape)
                .collect(Collectors.joining(","));
    }

    static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
