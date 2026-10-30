package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.MedicineTranscription;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PrintedBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The facts one চ্যাট answer may use (DR-50 envelope 2): the asked patient's stored values, their printed ranges and dates,
 * and the latest prescription's lines verbatim, read through {@link HealthUseCase} (family-scoped, zero model calls, no
 * audit row) and written as code-built lines. Nothing here is the model's; nothing is computed beyond what the health
 * screen shows.
 *
 * <p>Lines, in order: {@code PATIENT <name>}; per series (newest point's date first), its readings newest first, each
 * {@code VALUE <name>: <value> <unit> | printed range <text|none> | <INSIDE|OUTSIDE high|OUTSIDE low|OUTSIDE|UNKNOWN> |
 * paper <yyyy-MM-dd>}; blood-pressure pairs as one series; {@code PRESCRIPTION <date>} and {@code {{rx:n}} = <line>};
 * {@code NOT SHOWN <k> older readings} when the cap dropped any.
 *
 * <p>Under {@code baymax.chat.context-max-tokens} ({@link ChatContext#estimateTokens} of all lines): the oldest reading
 * of the longest series is dropped first, again and again. The prescription block and every series' newest reading are
 * never dropped that way; if they alone are over the cap, series are dropped whole, the oldest series first, and the
 * context says {@code truncated}. The closed set of numbers is built from the kept fields only.
 */
@Component
@RequiredArgsConstructor
public class ChatContextBuilder {

    static final String BP_NAME = "Blood pressure";

    private final HealthUseCase health;
    private final BaymaxProperties properties;

    /** One reading as a line, the numbers it allows, and its paper. */
    private record Fact(String line, Set<String> numbers, UUID documentId) {
    }

    public Mono<ChatContext> build(UUID familyId, UUID patientId, String patientName) {
        return Mono.zip(health.labs(familyId, patientId), health.medicines(familyId, patientId))
                .map(t -> assemble(patientName, t.getT1(), t.getT2(), properties.getChat().getContextMaxTokens()));
    }

    static ChatContext assemble(String patientName, HealthUseCase.HealthLabs labs, LatestPrescription rx, int cap) {
        // each series with its newest reading's date; blood-pressure pairs are one series among them, ordered alike
        record Dated(LocalDate newest, List<Fact> facts) {
        }
        List<Dated> dated = new ArrayList<>();
        for (LabSeries s : labs.series()) {
            List<Fact> facts = new ArrayList<>();
            for (LabSeries.Point p : s.points()) {
                facts.add(value(s.name(), p.value(), s.unit(), p.range(), verdict(p), p.date(), p.documentId()));
            }
            if (!facts.isEmpty()) {
                dated.add(new Dated(s.points().get(0).date(), facts));
            }
        }
        if (!labs.bp().points().isEmpty()) {
            List<Fact> bp = new ArrayList<>();
            for (LabSeries.BpPoint p : labs.bp().points()) {
                bp.add(bloodPressure(p));
            }
            dated.add(new Dated(labs.bp().points().get(0).date(), bp));
        }
        // newest first, a stable sort, so the health read's own order holds within a day
        dated.sort(java.util.Comparator.comparing(Dated::newest, java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>reverseOrder())));
        List<List<Fact>> series = new ArrayList<>();
        dated.forEach(d -> series.add(d.facts()));

        List<String> rxLines = new ArrayList<>();
        List<String> verbatim = new ArrayList<>();
        Set<String> rxNumbers = new LinkedHashSet<>();
        if (rx.found()) {
            rxLines.add("PRESCRIPTION " + (rx.date() == null ? "undated" : rx.date()));
            rxNumbers.addAll(AnswerCheck.dateNumbers(rx.date()));
            for (Map<String, Object> m : rx.medicines()) {
                String line = MedicineTranscription.line(m);
                // an abbreviated dosing line never reaches a family (DR-16); the model is not told of it either
                if (line.isEmpty() || !MedicineTranscription.elided(List.of(m)).isEmpty()) {
                    continue;
                }
                verbatim.add(line);
                rxLines.add("{{rx:" + verbatim.size() + "}} = " + line);
            }
        }

        String head = "PATIENT " + patientName;
        int dropped = 0;
        boolean truncated = false;
        while (tokens(head, series, rxLines, dropped) > cap) {
            List<Fact> longest = null;
            for (List<Fact> s : series) {
                if (s.size() > 1 && (longest == null || s.size() > longest.size())) {
                    longest = s;
                }
            }
            if (longest != null) {
                longest.remove(longest.size() - 1);
                dropped++;
            } else if (!series.isEmpty()) {
                // only newest readings left and still over: drop whole series, the oldest series first
                dropped += series.remove(series.size() - 1).size();
                truncated = true;
            } else {
                truncated = true;
                break;
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add(head);
        Set<String> numbers = new LinkedHashSet<>();
        Set<UUID> documents = new LinkedHashSet<>();
        for (List<Fact> s : series) {
            for (Fact f : s) {
                lines.add(f.line());
                numbers.addAll(f.numbers());
                if (f.documentId() != null) {
                    documents.add(f.documentId());
                }
            }
        }
        lines.addAll(rxLines);
        numbers.addAll(rxNumbers);
        if (rx.found() && !verbatim.isEmpty()) {
            documents.add(rx.documentId());
        }
        if (dropped > 0) {
            lines.add(notShown(dropped));
        }
        return new ChatContext(lines, numbers, verbatim, new ArrayList<>(documents), truncated);
    }

    private static int tokens(String head, List<List<Fact>> series, List<String> rxLines, int dropped) {
        List<String> lines = new ArrayList<>();
        lines.add(head);
        series.forEach(s -> s.forEach(f -> lines.add(f.line())));
        lines.addAll(rxLines);
        if (dropped > 0) {
            lines.add(notShown(dropped));
        }
        return ChatContext.estimateTokens(String.join("\n", lines));
    }

    private static String notShown(int k) {
        return "NOT SHOWN " + k + " older readings";
    }

    private static Fact value(String name, String value, String unit, PrintedBand range, String verdict, LocalDate date, UUID document) {
        String rangeText = range == null || range.text() == null ? "none" : range.text();
        String line = "VALUE " + name + ": " + value + (unit == null || unit.isBlank() ? "" : " " + unit)
                + " | printed range " + rangeText + " | " + verdict + " | paper " + (date == null ? "undated" : date);
        Set<String> numbers = new LinkedHashSet<>(AnswerCheck.numbersIn(value));
        if (range != null) {
            numbers.addAll(AnswerCheck.numbersIn(range.text()));
        }
        numbers.addAll(AnswerCheck.dateNumbers(date));
        return new Fact(line, numbers, document);
    }

    private static Fact bloodPressure(LabSeries.BpPoint p) {
        String s = p.systolic().value();
        String d = p.diastolic().value();
        String sr = p.systolic().range() == null || p.systolic().range().text() == null ? "none" : p.systolic().range().text();
        String dr = p.diastolic().range() == null || p.diastolic().range().text() == null ? "none" : p.diastolic().range().text();
        String line = "VALUE " + BP_NAME + ": " + s + "/" + d + " | printed range " + sr + " / " + dr + " | "
                + verdict(p.systolic()) + " / " + verdict(p.diastolic()) + " | paper " + (p.date() == null ? "undated" : p.date());
        Set<String> numbers = new LinkedHashSet<>(AnswerCheck.numbersIn(s));
        numbers.addAll(AnswerCheck.numbersIn(d));
        numbers.addAll(AnswerCheck.numbersIn(p.systolic().range() == null ? null : p.systolic().range().text()));
        numbers.addAll(AnswerCheck.numbersIn(p.diastolic().range() == null ? null : p.diastolic().range().text()));
        numbers.addAll(AnswerCheck.dateNumbers(p.date()));
        return new Fact(line, numbers, p.documentId());
    }

    /** The point's drawn kind as the paper page shows it; a critical reading is outside, its side as stored. */
    static String verdict(LabSeries.Point p) {
        ValueVerdict.Kind kind = p.kind();
        if (kind == ValueVerdict.Kind.INSIDE) {
            return "INSIDE";
        }
        if (kind == ValueVerdict.Kind.OUTSIDE || kind == ValueVerdict.Kind.CRITICAL) {
            return p.side() == null ? "OUTSIDE" : "OUTSIDE " + p.side();
        }
        return "UNKNOWN";
    }
}
