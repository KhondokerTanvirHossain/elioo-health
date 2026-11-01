package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-50 "Explain, never change", in code: the guard in both directions on both sides, written before the guard. An answer
 * row is the model's text and runs through {@link AnswerCheck} exactly as the pipeline runs it, so a stored prescription
 * line written as its {@code {{rx:n}}} token is exempt (it IS the paper) while the model's own words are not. A question
 * row decides whether code appends the ask-the-doctor line. Each row is its own assertion, named by its line number.
 */
class DoseChangeGuardTest {

    static final String RX1 = "Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে";
    static final String RX2 = "Tab. Demoprol 5 mg · ১+০+০ · খাবারের পরে খাবেন · ৩০ দিন";
    static final java.time.LocalDate RX_DATE = java.time.LocalDate.parse("2026-09-20");

    /** {@code code}, when the row has a 4th column, is the {@code dose_change:} code that must refuse it; else null. */
    record Row(int line, String side, String expect, String text, String code) {
    }

    static List<Row> corpus() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = DoseChangeGuardTest.class.getResourceAsStream("/chat/dose-change-corpus.tsv")) {
            assertThat(in).as("the corpus").isNotNull();
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                String[] cols = l.split("\t", 4);
                assertThat(cols.length).as("line " + (i + 1) + " has three or four tab-separated columns").isBetween(3, 4);
                String code = cols.length == 4 ? cols[3] : null;
                if (code != null) {
                    assertThat(cols[0] + "/" + cols[1]).as("line " + (i + 1) + ": a code names the rule of an answer flag")
                            .isEqualTo("answer/flag");
                    assertThat(code).as("line " + (i + 1)).startsWith("dose_change:");
                }
                rows.add(new Row(i + 1, cols[0], cols[1], cols[2], code));
            }
        }
        return rows;
    }

    @Test
    void theCorpusInBothDirections() throws IOException {
        List<Row> rows = corpus();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Row r : rows) {
            groups.merge(r.side() + "/" + r.expect(), 1, Integer::sum);
        }
        assertThat(groups.keySet()).containsExactlyInAnyOrder("answer/flag", "answer/pass", "question/flag", "question/pass");
        groups.forEach((group, n) -> assertThat(n).as("rows in " + group).isGreaterThanOrEqualTo(20));

        // the prescription's date, so a row may name it whole; the medicine's name, as the builder reads it from the line
        ChatContext ctx = new ChatContext(List.of(), AnswerCheck.yearOf(RX_DATE), List.of(RX1, RX2), List.of(), false, List.of(),
                Set.of(RX_DATE), ChatContext.namesIn("Tab. Demoprol"));
        List<String> wrong = new ArrayList<>();
        int coded = 0;
        for (Row r : rows) {
            List<String> codes = "answer".equals(r.side()) ? AnswerCheck.violations(r.text(), ctx) : List.of();
            if (r.code() != null) {
                coded++;
                if (!codes.contains(r.code())) {
                    wrong.add("row " + r.line() + ": expected " + r.code() + " but was " + codes + ": " + r.text());
                }
            }
            boolean flagged = switch (r.side()) {
                case "answer" -> codes.stream().anyMatch(v -> v.startsWith("dose_change:"));
                case "question" -> DoseChangeGuard.asksForChange(r.text(), ctx.medicineNames());
                default -> throw new AssertionError("row " + r.line() + ": unknown side " + r.side());
            };
            if (flagged != "flag".equals(r.expect())) {
                wrong.add("row " + r.line() + ": expected " + r.expect() + " (" + r.side() + "): " + r.text());
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(coded).as("rows that name the rule which refuses them").isGreaterThanOrEqualTo(6);
    }

    @Test
    void anInstructionIsReportedByItsTerm() {
        assertThat(DoseChangeGuard.instructs("অর্ধেক করে খান")).contains("dose_change:অর্ধেক", "dose_change:খান");
        assertThat(DoseChangeGuard.instructs("Demoprol is generally used for blood pressure.")).isEmpty();
        assertThat(DoseChangeGuard.instructs("Give her Demoprol only in the morning.", Set.of("demoprol")))
                .as("a word harmless alone, beside a stored medicine's name").contains("dose_change:give+medicine");
        assertThat(DoseChangeGuard.instructs("I can't give a reliable answer to this right now.", Set.of("demoprol")))
                .as("the same word with no medicine in the sentence").isEmpty();
    }
}
