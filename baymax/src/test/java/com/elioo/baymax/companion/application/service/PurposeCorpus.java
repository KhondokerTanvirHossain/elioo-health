package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.TextNormaliser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PO ruling P14's corpus ({@code chat/medicine-purpose-corpus.tsv}) and the context its rows are read against: read by the
 * guard's own test and by the service test that asserts each question's PATH, so both run the one list.
 */
final class PurposeCorpus {

    static final String RX1 = "Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে";
    static final String RX2 = "Tab. Calmora 10 mg · 0+0+1 · ঘুমের জন্য";
    static final LocalDate RX_DATE = LocalDate.parse("2026-09-20");
    static final UUID PAPER = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100014");

    record Row(int line, String side, String expect, String text) {
    }

    private PurposeCorpus() {
    }

    static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = PurposeCorpus.class.getResourceAsStream("/chat/medicine-purpose-corpus.tsv")) {
            assertThat(in).as("the corpus").isNotNull();
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                String[] cols = l.split("\t", 3);
                assertThat(cols.length).as("line " + (i + 1) + " has three tab-separated columns").isEqualTo(3);
                rows.add(new Row(i + 1, cols[0], cols[1], cols[2]));
            }
        }
        assertThat(rows).as("rows in the corpus (an empty corpus would pass vacuously)").hasSizeGreaterThan(100);
        return rows;
    }

    /** Two stored lines (the second's paper prints what it is for), a stored lab, the patient Ma. */
    static ChatContext context() {
        List<ChatContext.Fact> facts = List.of(new ChatContext.Fact("Creatinine", "mg/dL", Set.of("1.8", "0.7", "1.3"), "OUTSIDE high"));
        Set<String> numbers = new LinkedHashSet<>(Set.of("1.8", "0.7", "1.3"));
        numbers.addAll(AnswerCheck.yearOf(RX_DATE));
        Set<String> names = new LinkedHashSet<>(ChatContext.namesIn("Tab. Demoprol"));
        names.addAll(ChatContext.namesIn("Tab. Calmora"));
        return new ChatContext(List.of("PATIENT Ma", "VALUE Creatinine: 1.8 mg/dL | 0.7–1.3 | OUTSIDE high | 2026-09-12",
                "PRESCRIPTION 2026-09-20", "{{rx:1}} = " + RX1, "{{rx:2}} = " + RX2), numbers, List.of(RX1, RX2), List.of(PAPER),
                false, facts, Set.of(RX_DATE), names);
    }

    /** The stored labs' names in normal form, as AnswerCheck reads them. */
    static Set<String> labNames(ChatContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        ctx.facts().forEach(f -> out.add(TextNormaliser.normalise(f.name())));
        return out;
    }
}
