package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PO ruling P14 ("Only what the papers say"), layer 2: the answer rows of the corpus ({@link PurposeCorpus}, written before
 * the guard), both directions. Each row is the model's text and runs through {@link AnswerCheck#violations} exactly as the
 * pipeline runs it, so a stored line written as its {@code {{rx:n}}} token is exempt (it IS the paper) while the model's
 * own words are not; a flag row must carry a {@code medicine_purpose} code and a pass row none. Only the public check is
 * used, so this test runs unchanged against the code before the guard existed (its red replay).
 */
class MedicinePurposeAnswerCorpusTest {

    @Test
    void theAnswerRowsInBothDirections() throws IOException {
        ChatContext ctx = PurposeCorpus.context();
        Map<String, Integer> groups = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        for (PurposeCorpus.Row r : PurposeCorpus.rows()) {
            if (!"answer".equals(r.side())) {
                continue;
            }
            groups.merge(r.expect(), 1, Integer::sum);
            List<String> codes = AnswerCheck.violations(r.text(), ctx);
            boolean refused = codes.stream().anyMatch(c -> c.startsWith("medicine_purpose"));
            if (refused != "flag".equals(r.expect())) {
                wrong.add("row " + r.line() + ": expected " + r.expect() + " (answer) but was " + codes + ": " + r.text());
            }
        }
        assertThat(groups.keySet()).containsExactlyInAnyOrder("flag", "pass");
        groups.forEach((group, n) -> assertThat(n).as("answer rows in " + group).isGreaterThanOrEqualTo(20));
        assertThat(wrong).isEmpty();
    }
}
