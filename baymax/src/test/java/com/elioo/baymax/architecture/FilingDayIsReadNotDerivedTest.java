package com.elioo.baymax.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2: the day a paper is filed under is defined once, by Postgres ({@code document.timeline_date} and
 * {@code timeline_date_is_upload_day}, V24), and every reader reads it. Before D2 the same rule was restated wherever a
 * paper was described ({@code d.docDate() != null ? d.docDate() : upload day}, {@code doc_date IS NULL AS
 * date_is_fallback}), and each restatement filed a retake under the date misread off its blurred page. This scan finds
 * every place in {@code src/main/java} that reads the printed date or re-derives the fallback, and fails on any not in
 * the allow-list below — each entry a file, the exact number of reads it holds, and why it legitimately needs the
 * printed date rather than the filing day. A new read anywhere, or a second one in an allowed file, fails here.
 */
class FilingDayIsReadNotDerivedTest {

    /** A read of the printed date, or a restatement of the fallback rule, in Java or in SQL held in Java. */
    static final Pattern READS_THE_PRINTED_DATE = Pattern.compile(
            "docDate\\(\\)"
                    + "|get\\(\"doc_date\""
                    + "|(?i:\\bdoc_date\\s+IS\\s+(?:NOT\\s+)?NULL)"
                    + "|(?i:\\bcoalesce\\(\\s*(?:[a-z_]+\\.)?doc_date\\b)");

    /** file → (reads it holds, why it needs the printed date and not the filing day). */
    private static final Map<String, Allowed> ALLOWED = Map.of(
            "DocumentEntity.java", new Allowed(1,
                    "the mapping: it writes the printed doc_date column from the record"),
            "PostgresDocumentRecordAdapter.java", new Allowed(3,
                    "the write (finish's UPDATE binds the printed date); and the medicine_changed nudge withdrawal, which "
                            + "compares two DONE prescriptions' printed dates (a retake stores no medicines)"),
            "DocumentIntakeService.java", new Allowed(2,
                    "the DONE view's printed date as printed; PaperJson sends it as printed_date beside the filing day"),
            "DocumentFacts.java", new Allowed(7,
                    "the safety check's allowed numbers: the digits of the date printed on a DONE paper, which the "
                            + "model may quote"),
            "ExplanationService.java", new Allowed(4,
                    "the explanation and detail of a DONE paper name the date printed on it, and nothing when none was "
                            + "printed, never an unmarked upload day; a retake's message names no date"),
            "NudgeRules.java", new Allowed(4,
                    "a course of medicine starts on the prescription's printed date, and medicine_changed names it; "
                            + "medication rows exist only on DONE papers"),
            "PostgresNudgeDataAdapter.java", new Allowed(2,
                    "reads the printed date for NudgeRules' medication rows, DONE papers only"));

    /** The files the defect lived in: the scan must have read each of them, or it proves nothing about them. */
    private static final Set<String> MUST_HAVE_READ = Set.of("WaIntakeService.java", "PaperJson.java", "DocumentPages.java",
            "PostgresTimelineReadAdapter.java", "PostgresDocumentReadAdapter.java");

    record Allowed(int reads, String reason) {
    }

    @Test
    void nobodyRederivesThePapersDate() throws IOException {
        Map<String, List<String>> hits = new TreeMap<>();
        Set<String> read = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                read.add(name);
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = READS_THE_PRINTED_DATE.matcher(lines.get(i));
                    while (m.find()) {
                        hits.computeIfAbsent(name, k -> new ArrayList<>()).add(p + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(read).as("the scan read the files the defect lived in").containsAll(MUST_HAVE_READ);
        assertThat(read.size()).as("the scan read the main source tree, not an empty directory").isGreaterThanOrEqualTo(200);

        List<String> wrong = new ArrayList<>();
        hits.forEach((file, at) -> {
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null || allowed.reads() != at.size()) {
                wrong.add(file + (allowed == null ? " (not allowed)" : " (allowed " + allowed.reads() + ", found " + at.size() + ")")
                        + "\n    " + String.join("\n    ", at));
            }
        });
        assertThat(wrong).as("reads of the printed date outside the allow-list: read document.timeline_date "
                + "(Document.timelineDate(), timeline_date_is_upload_day) instead").isEmpty();
        assertThat(hits.keySet()).as("every allow-list entry still holds the reads it names (a stale entry is a hole)")
                .isEqualTo(ALLOWED.keySet());
    }

    /** The emptiness guard for the pattern: it sees every spelling the defect used, and not the column the fix reads. */
    @Test
    void thePatternSeesEveryRestatementAndNotTheFilingDay() {
        assertThat(READS_THE_PRINTED_DATE.matcher("LocalDate date = d.docDate() != null ? d.docDate() : x;").find()).isTrue();
        assertThat(READS_THE_PRINTED_DATE.matcher("d.doc_date IS NULL AS date_is_fallback,").find()).isTrue();
        assertThat(READS_THE_PRINTED_DATE.matcher("WHERE doc_date is not null").find()).isTrue();
        assertThat(READS_THE_PRINTED_DATE.matcher("row.get(\"doc_date\", LocalDate.class)").find()).isTrue();
        assertThat(READS_THE_PRINTED_DATE.matcher("ORDER BY COALESCE(d.doc_date, d.created_at::date) DESC").find()).isTrue();
        assertThat(READS_THE_PRINTED_DATE.matcher("d.timeline_date_is_upload_day AS date_is_fallback").find()).isFalse();
        assertThat(READS_THE_PRINTED_DATE.matcher("d.timelineDate() != null ? d.timelineDate() : today").find()).isFalse();
        assertThat(READS_THE_PRINTED_DATE.matcher("parseDate(result.documentDate())").find()).isFalse();
    }
}
