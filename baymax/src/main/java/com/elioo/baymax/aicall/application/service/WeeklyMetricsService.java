package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.in.WeeklyMetricsUseCase;
import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
 * <p><b>families</b>: one row per family with plan, patient count, documents created in the window,
 * nudges sent/answered (0 until the proactive engine exists) and days since the last document, measured
 * at the window end so an export is reproducible.</p>
 */
@Service
@RequiredArgsConstructor
public class WeeklyMetricsService implements WeeklyMetricsUseCase {

    static final String DOCUMENT_HEADER = "document_id,calls,unpriced_calls,input_tokens,output_tokens,"
            + "cost_usd,models,avg_confidence,unverified_items,first_call_at,last_call_at";
    static final String FAMILY_HEADER = "family_id,plan,patients,documents,nudges_sent,nudges_answered,days_since_last_document";

    private final AiCallLogPort callLog;
    private final HealthRecordPort records;
    /** BMX-5: OTP traffic per (hashed) number per day, and views per family. */
    private final com.elioo.baymax.web.application.port.out.AuditPort audit;
    /** BMX-6: urgency distribution and gate outcomes. */
    private final com.elioo.baymax.outbound.application.port.out.OutboundMessagePort messages;

    static final String MESSAGES_HEADER = "urgency,gate_status,messages";
    /** Extraction output tokens per call: two walkthrough documents ran to 7,059 and 8,192 (the cap). Watch, do not cap yet. */
    static final String EXTRACTION_HEADER = "extract_calls,output_tokens_max,output_tokens_p95,output_tokens_median";

    static final String AUTH_HEADER = "day,phone_hash,otp_requests,otp_verify_ok,otp_verify_failed";
    static final String VIEWS_HEADER = "family_id,timeline_views,document_views";

    @Override
    public Mono<String> weeklyCsv(Instant from, Instant to) {
        return Mono.zip(callLog.perDocument(from, to).collectList(),
                        records.familyActivity(from, to).collectList(),
                        audit.otpPerNumberPerDay(from, to).collectList(),
                        audit.viewsPerFamily(from, to).collectList(),
                        messages.counts(from, to).collectList(),
                        callLog.extractOutputTokens(from, to).collectList())
                .map(t -> render(t.getT1(), t.getT2(), from, to) + renderAuth(t.getT3(), t.getT4(), from, to)
                        + renderMessages(t.getT5(), from, to) + renderExtraction(t.getT6(), from, to));
    }

    static String render(List<DocumentAiCost> rows, List<FamilyActivity> families, Instant from, Instant to) {
        String window = "from=" + from + " to=" + to + " (to exclusive)";
        StringBuilder csv = new StringBuilder();
        csv.append("# documents ").append(window).append('\n').append(DOCUMENT_HEADER).append('\n');
        for (DocumentAiCost row : rows) {
            csv.append(line(row)).append('\n');
        }
        csv.append('\n').append("# families ").append(window).append('\n').append(FAMILY_HEADER).append('\n');
        for (FamilyActivity f : families) {
            csv.append(familyLine(f, to)).append('\n');
        }
        return csv.toString();
    }

    private static String familyLine(FamilyActivity f, Instant to) {
        String daysSince = f.lastDocumentAt() == null ? ""
                : Long.toString(Math.max(0, Duration.between(f.lastDocumentAt(), to).toDays()));
        return Stream.of(
                        f.familyId().toString(),
                        f.plan().dbValue(),
                        Long.toString(f.patients()),
                        Long.toString(f.documentsInWindow()),
                        "0",
                        "0",
                        daysSince)
                .map(WeeklyMetricsService::csvEscape)
                .collect(Collectors.joining(","));
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
                        Long.toString(r.unverifiedItems()),
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

    /** Two more blocks (BMX-5): OTP traffic per number per day, and timeline/document views per family. */
    static String renderAuth(java.util.List<com.elioo.baymax.web.domain.OtpDailyCount> otp,
                             java.util.List<com.elioo.baymax.web.domain.FamilyViewCount> views, Instant from, Instant to) {
        String window = "from=" + from + " to=" + to + " (to exclusive)";
        StringBuilder csv = new StringBuilder();
        csv.append('\n').append("# auth ").append(window).append('\n').append(AUTH_HEADER).append('\n');
        for (var o : otp) {
            csv.append(o.day()).append(',').append(o.phoneHash()).append(',').append(o.requests()).append(',')
                    .append(o.verifiesOk()).append(',').append(o.verifiesFailed()).append('\n');
        }
        csv.append('\n').append("# views ").append(window).append('\n').append(VIEWS_HEADER).append('\n');
        for (var v : views) {
            csv.append(v.familyId()).append(',').append(v.timelineViews()).append(',').append(v.documentViews()).append('\n');
        }
        return csv.toString();
    }

    static String renderMessages(java.util.List<com.elioo.baymax.outbound.domain.MessageCount> counts, Instant from, Instant to) {
        StringBuilder csv = new StringBuilder();
        csv.append('\n').append("# messages from=").append(from).append(" to=").append(to).append(" (to exclusive)").append('\n')
                .append(MESSAGES_HEADER).append('\n');
        for (var c : counts) {
            csv.append(c.urgency().dbValue()).append(',').append(c.gateStatus().dbValue()).append(',').append(c.count()).append('\n');
        }
        return csv.toString();
    }

    static String renderExtraction(java.util.List<Integer> outputTokens, Instant from, Instant to) {
        java.util.List<Integer> sorted = new java.util.ArrayList<>(outputTokens);
        java.util.Collections.sort(sorted);
        StringBuilder csv = new StringBuilder();
        csv.append('\n').append("# extraction from=").append(from).append(" to=").append(to).append(" (to exclusive)").append('\n')
                .append(EXTRACTION_HEADER).append('\n');
        if (!sorted.isEmpty()) {
            csv.append(sorted.size()).append(',').append(sorted.get(sorted.size() - 1)).append(',')
                    .append(percentile(sorted, 95)).append(',').append(percentile(sorted, 50)).append('\n');
        }
        return csv.toString();
    }

    /** Nearest-rank percentile on a sorted list. */
    static int percentile(java.util.List<Integer> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
