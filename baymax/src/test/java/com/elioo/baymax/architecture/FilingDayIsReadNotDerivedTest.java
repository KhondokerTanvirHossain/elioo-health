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
 * D2: the day a paper is filed under is defined once, by Postgres ({@code document.timeline_date},
 * {@code timeline_date_is_upload_day} and {@code timeline_date_uncertain}, V25), and every reader reads it. Before D2 the
 * same rule was restated wherever a paper was described ({@code d.docDate() != null ? d.docDate() : upload day},
 * {@code doc_date IS NULL AS date_is_fallback}, {@code PatientCalendar.dayOf(receivedAt)} beside
 * {@code v.documentDate()}), and each restatement filed a retake under the date misread off its blurred page.
 *
 * <p>This scan finds, in every non-comment line of {@code src/main/java}, each way there is to reach the printed date or
 * the upload-day half of the rule — the record accessor and its method reference, the view's printed date, any bare
 * {@code doc_date} in SQL held in Java (not {@code doc_date_uncertain}), the patient's calendar day of an instant, the
 * Dhaka cast in SQL, and a {@code LocalDate} made from an upload instant — and fails on any not in the allow-list below:
 * each entry a file by its path under {@code src/main/java}, the exact number of hits it holds, and why it legitimately
 * needs that read and not the filed day. A new read anywhere, or one more in an allowed file, fails here naming the line.
 *
 * <p>The pattern is held to the defect's real spellings by {@link #thePatternSeesEveryRestatementAndNotTheFiledDay}: the
 * lines of the pre-D2 {@code PaperJson.dates}, {@code DocumentPages.paperDateText}, {@code WaIntakeService.paperOf} and
 * {@code PostgresTimelineReadAdapter} are quoted there verbatim and each must match.
 */
class FilingDayIsReadNotDerivedTest {

    /** Every route to the printed date, or to a re-derived upload day, in Java or in SQL held in Java. */
    static final Pattern READS_THE_PRINTED_DATE = Pattern.compile(
            "docDate\\(\\)"                                           // the record accessor
                    + "|::docDate\\b"                                  // its method reference
                    + "|\\.documentDate\\(\\)"                         // the view's printed date (and ExtractionResult's)
                    + "|\\bdoc_date\\b(?!_uncertain)"                  // the column, in SQL text or a row read
                    + "|PatientCalendar\\.dayOf\\("                    // the patient's calendar day of an instant
                    + "|(?i:AT\\s+TIME\\s+ZONE\\s+'Asia/Dhaka')"       // the same, in SQL
                    + "|LocalDate\\.ofInstant\\([^;]*?(?:createdAt|receivedAt)\\(\\)");   // a day made from an upload instant

    /** path under src/main/java → (hits it holds, why it needs that read and not the filed day). */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
            Map.entry("com/elioo/baymax/extraction/adapter/out/persistence/DocumentEntity.java", new Allowed(2,
                    "the mapping: the doc_date column and the record's printed date written into it")),
            Map.entry("com/elioo/baymax/extraction/adapter/out/persistence/PostgresDocumentRecordAdapter.java", new Allowed(2,
                    "the write: finish's UPDATE sets doc_date and binds the printed date")),
            Map.entry("com/elioo/baymax/extraction/application/service/DocumentExtractionService.java", new Allowed(2,
                    "the model's document_date (ExtractionResult): finish parses it into the printed date it writes, and "
                            + "observedAt stamps a DONE paper's values with it (items are stored on DONE only), never a display")),
            Map.entry("com/elioo/baymax/extraction/application/service/DocumentIntakeService.java", new Allowed(2,
                    "a DONE view's printed date as printed; every other status gets the status-only view")),
            Map.entry("com/elioo/baymax/extraction/adapter/in/handler/DocumentHandler.java", new Allowed(1,
                    "the legacy /documents/{id} body's document_date: the view's printed date, present on DONE only")),
            Map.entry("com/elioo/baymax/web/adapter/in/api/PaperJson.java", new Allowed(1,
                    "printed_date sent as printed, and only when the filed day is not the upload day")),
            Map.entry("com/elioo/baymax/web/adapter/in/ui/DocumentPages.java", new Allowed(2,
                    "the legacy render()'s printed date (DONE views only), and the retake page's \"sent\" line, "
                            + "the day the photo arrived, never shown as the paper's date")),
            Map.entry("com/elioo/baymax/web/adapter/in/ui/TimelineView.java", new Allowed(1,
                    "the \"sent\" label: the day the photo arrived, shown beside the filed day, never as it")),
            Map.entry("com/elioo/baymax/web/adapter/in/ui/FamilyView.java", new Allowed(1,
                    "the day a proxy consent was given, on the patient's calendar; not a paper's date")),
            Map.entry("com/elioo/baymax/web/adapter/in/handler/TimelineApiHandler.java", new Allowed(1,
                    "the legacy JSON key named doc_date, whose value is the filed day (TimelineEntry.date())")),
            Map.entry("com/elioo/baymax/outbound/application/service/DocumentFacts.java", new Allowed(7,
                    "the safety check's allowed numbers: the digits of the date printed on a DONE paper, which the "
                            + "model may quote")),
            Map.entry("com/elioo/baymax/outbound/application/service/ExplanationService.java", new Allowed(4,
                    "the explanation and detail of a DONE paper name the date printed on it, and nothing when none was "
                            + "printed, never an unmarked upload day; a retake's message names no date")),
            Map.entry("com/elioo/baymax/nudge/application/service/NudgeRules.java", new Allowed(4,
                    "a course of medicine starts on the prescription's printed date, and medicine_changed names it; "
                            + "medication rows exist only on DONE papers")),
            Map.entry("com/elioo/baymax/nudge/adapter/out/persistence/PostgresNudgeDataAdapter.java", new Allowed(2,
                    "selects and reads the printed date for NudgeRules' medication rows (DONE papers only); the order "
                            + "is the filed day")));

    /**
     * The files the defect lived in. Reading them proves nothing by itself (a file that exists is not a file without the
     * defect); it guards the scan's root. That the pattern catches what those files used to say is the self-test's job,
     * and replaying the old methods against this test is the PR's.
     */
    private static final Set<String> MUST_HAVE_READ = Set.of(
            "com/elioo/baymax/wa/application/service/WaIntakeService.java",
            "com/elioo/baymax/web/adapter/in/api/PaperJson.java",
            "com/elioo/baymax/web/adapter/in/ui/DocumentPages.java",
            "com/elioo/baymax/web/adapter/out/persistence/PostgresTimelineReadAdapter.java",
            "com/elioo/baymax/extraction/adapter/out/persistence/PostgresDocumentReadAdapter.java");

    record Allowed(int reads, String reason) {
    }

    /** A line that is only a comment: javadoc and block-comment bodies, and line comments. */
    private static boolean commentOnly(String line) {
        String s = line.strip();
        return s.startsWith("*") || s.startsWith("/*") || s.startsWith("//");
    }

    @Test
    void nobodyRederivesThePapersDate() throws IOException {
        Path root = Path.of("src/main/java");
        Map<String, List<String>> hits = new TreeMap<>();
        Set<String> read = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                read.add(rel);
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (commentOnly(lines.get(i))) {
                        continue;
                    }
                    Matcher m = READS_THE_PRINTED_DATE.matcher(lines.get(i));
                    while (m.find()) {
                        hits.computeIfAbsent(rel, k -> new ArrayList<>()).add(rel + ":" + (i + 1) + ": " + lines.get(i).strip());
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
        assertThat(wrong).as("reads of the printed date or a re-derived upload day outside the allow-list: read the filed "
                + "day (Document.filed(), document.timeline_date / timeline_date_is_upload_day / timeline_date_uncertain) instead")
                .isEmpty();
        assertThat(hits.keySet()).as("every allow-list entry still holds the reads it names (a stale entry is a hole)")
                .isEqualTo(ALLOWED.keySet());
    }

    /**
     * The emptiness guard for the pattern: it sees every spelling the defect used — each pre-D2 line quoted verbatim from
     * the file it lived in — and the shapes a new reader would take, and it does not see the filed-day reads the fix uses.
     */
    @Test
    void thePatternSeesEveryRestatementAndNotTheFiledDay() {
        List<String> restatements = List.of(
                // WaIntakeService.paperOf and its Urgent card, before D2
                "LocalDate date = d.docDate() != null ? d.docDate()",
                ": d.createdAt() != null ? LocalDate.ofInstant(d.createdAt(), DHAKA) : LocalDate.now(clock.withZone(DHAKA));",
                "newest.docDate() == null, newest.createdAt()))",
                // PostgresTimelineReadAdapter and PostgresDocumentReadAdapter, before D2
                "d.doc_date IS NULL AS date_is_fallback,",
                // PaperJson.dates, before D2
                "dates(m, v.documentDate(), r.receivedAt());",
                "m.put(\"date\", receivedAt == null ? null : PatientCalendar.dayOf(receivedAt).toString());",
                // DocumentPages.paperDateText, before D2
                "String printed = r.document().documentDate();",
                ": copy.dateText(lang, PatientCalendar.dayOf(r.receivedAt())) + \" \" + copy.raw(lang, \"timeline.uploaddate\");",
                // PostgresNudgeDataAdapter and PostgresDocumentRecordAdapter's UTC-day orderings, before review
                "ORDER BY COALESCE(d.doc_date, d.created_at::date) DESC, d.created_at DESC, m.name",
                "AND COALESCE(x.doc_date, x.created_at::date) >= COALESCE(me.doc_date, me.created_at::date))))",
                // shapes a new reader would take
                "row.get(\"doc_date\", LocalDate.class)",
                "WHERE doc_date is not null",
                "SELECT d.doc_date AS date, d.id FROM baymax.document d",
                "m.put(\"date\", v.documentDate());",
                "documents.map(Document::docDate)",
                "rows.stream().map(MedicationRow::docDate)",
                "(created_at AT TIME ZONE 'Asia/Dhaka')::date",
                "LocalDate sent = LocalDate.ofInstant(r.receivedAt(), PatientCalendar.ZONE);");
        assertThat(restatements).allSatisfy(line ->
                assertThat(READS_THE_PRINTED_DATE.matcher(line).find()).as("the pattern sees: " + line).isTrue());

        List<String> filedDayReads = List.of(
                "d.timeline_date_is_upload_day AS date_is_fallback, d.timeline_date_uncertain AS date_uncertain,",
                "ORDER BY d.timeline_date DESC, d.created_at DESC, m.name",
                "document_type = :type, doc_date_uncertain = :dateUncertain,",
                "FiledDay filed = d.filed();",
                "m.put(\"date\", filed.date().toString());",
                "LocalDate.ofInstant(clock.instant(), zone())");
        assertThat(filedDayReads).allSatisfy(line ->
                assertThat(READS_THE_PRINTED_DATE.matcher(line).find()).as("the pattern does not see: " + line).isFalse());
    }

    /**
     * Review findings 5, 11, 17: D2 delivers {@code date_uncertain} on papers; the plan also puts it on B3's timeline
     * entries and attention cards and B6's lab points, which are not on main as D2 is written. This trips the moment one
     * of those files lands without the mark, and the moment B3's timeline JSON lands without the plan's month test.
     * Until then it reads nothing (by design: it is a tripwire, not the check — the check is B3's and B6's contract
     * tests, which the plan's B3 and B6 tasks name).
     */
    @Test
    void theTimelineAttentionAndHealthJsonCarryTheUncertainMarkWhenTheyLand() throws IOException {
        Path api = Path.of("src/main/java/com/elioo/baymax/web/adapter/in/api");
        assertThat(Files.readString(api.resolve("PaperJson.java"))).as("the paper JSON, which D2 ships").contains("\"date_uncertain\"");
        for (String file : List.of("TimelineJson.java", "AttentionApiHandler.java", "HealthJson.java")) {
            Path p = api.resolve(file);
            if (Files.exists(p)) {
                assertThat(Files.readString(p)).as(file + " carries date_uncertain (plan D2 Interfaces; B3/B6 tasks)")
                        .contains("\"date_uncertain\"");
            }
        }
        if (Files.exists(api.resolve("TimelineJson.java"))) {
            Path contract = Path.of("src/test/java/com/elioo/baymax/web/adapter/in/api/PatientTimelineApiContractTest.java");
            assertThat(contract).as("B3's timeline contract test").exists();
            assertThat(Files.readString(contract)).as("plan D2 Step 1 / Review Focus 5, on the month key the client reads")
                    .contains("monthGroupingAcrossADhakaMidnight");
        }
    }
}
