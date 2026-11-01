package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.MedicineTranscription;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PrintedBand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan CHAT-api Task 4: the context is the asked patient's stored facts only (DR-50 envelope 2), built by code, under a
 * token cap that drops the oldest readings first and never the prescription or a series' newest reading. The closed set
 * of numbers is the kept fields, never the prompt string. Facts are matched by field, never as a substring of the whole.
 */
class ChatContextBuilderTest {

    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000001");
    static final UUID ASKED = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000002");
    static final UUID OTHER = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000003");
    /** A VALUE line's fields: name, value, unit; then the range, the verdict and the paper, each a label field. */
    static final Pattern VALUE = Pattern.compile("^VALUE (.+): (\\S+)(?: (.*))? \\| (.*) \\| (.*) \\| (.*)$");
    static final UiChatCopyAdapter COPY = new UiChatCopyAdapter(new UiCopy(), new Copy());
    static final ChatLabels BN = ChatLabels.of("bn", COPY);
    static final ChatLabels EN = ChatLabels.of("en", COPY);
    static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");
    static final Pattern BENGALI = Pattern.compile("\\p{IsBengali}");

    private final HealthUseCase health = mock(HealthUseCase.class);

    private ChatContextBuilder builder(int cap) {
        BaymaxProperties p = new BaymaxProperties();
        p.getChat().setContextMaxTokens(cap);
        return new ChatContextBuilder(health, p);
    }

    static LabSeries.Point point(UUID document, String value, LocalDate date) {
        return new LabSeries.Point(document, UUID.randomUUID(), value, date, false, false, ValueVerdict.Kind.OUTSIDE, "high", "k",
                PrintedBand.of("0.7", "1.3"));
    }

    static LabSeries series(String name, String unit, List<LabSeries.Point> points) {
        return new LabSeries(name.toLowerCase(), unit, name, unit, points);
    }

    private void labs(UUID patient, List<LabSeries> series) {
        when(health.labs(FAMILY, patient)).thenReturn(Mono.just(new HealthUseCase.HealthLabs(series, new LabSeries.BpSeries(List.of()))));
    }

    private void noPrescription(UUID patient) {
        when(health.medicines(FAMILY, patient)).thenReturn(Mono.just(LatestPrescription.none(Optional.empty())));
    }

    /** One VALUE line, split into its fields. */
    record ValueLine(String line, String name, String value, String unit, String range, String verdict, String paper) {
    }

    /** Every VALUE line, parsed field by field, never a substring of the whole context. */
    static List<ValueLine> valueLines(ChatContext ctx) {
        List<ValueLine> out = new ArrayList<>();
        for (String line : ctx.factLines()) {
            if (line.startsWith("VALUE ")) {
                Matcher m = VALUE.matcher(line);
                assertThat(m.matches()).as("a VALUE line in the agreed shape: " + line).isTrue();
                out.add(new ValueLine(line, m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6)));
            }
        }
        return out;
    }

    /** The value field of every VALUE line, parsed, never a substring of the whole context. */
    static List<String> values(ChatContext ctx) {
        List<String> out = new ArrayList<>();
        for (String line : ctx.factLines()) {
            if (line.startsWith("VALUE ")) {
                Matcher m = VALUE.matcher(line);
                assertThat(m.matches()).as("a VALUE line in the agreed shape: " + line).isTrue();
                out.add(m.group(2));
            }
        }
        return out;
    }

    @Test
    void onlyTheAskedPatientsFactsAreInTheContext() {
        UUID mine = UUID.randomUUID();
        labs(ASKED, List.of(series("Creatinine", "mg/dL", List.of(point(mine, "1.8", LocalDate.parse("2026-09-12"))))));
        labs(OTHER, List.of(series("Creatinine", "mg/dL", List.of(point(UUID.randomUUID(), "7.77", LocalDate.parse("2026-09-12"))))));
        noPrescription(ASKED);
        noPrescription(OTHER);

        ChatContext ctx = builder(6000).build(FAMILY, ASKED, "Ma", BN).block();

        assertThat(values(ctx)).containsExactly("1.8");
        assertThat(ctx.numbers()).contains("1.8", "0.7", "1.3", "2026").doesNotContain("7.77")
                .as("a paper's day and month are not free numbers (review of #164)").doesNotContain("9", "12");
        assertThat(ctx.dates()).containsExactly(LocalDate.parse("2026-09-12"));
        assertThat(ctx.documentIds()).containsExactly(mine);
        assertThat(ctx.factLines().get(0)).isEqualTo("PATIENT Ma");
    }

    @Test
    void theTokenCapDropsTheOldestReadingsFirst() {
        List<LabSeries> five = new ArrayList<>();
        Map<String, String> newest = new LinkedHashMap<>();
        for (int s = 0; s < 5; s++) {
            List<LabSeries.Point> points = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                // newest first; every value unique, so each kept or dropped reading is identified by its value
                String v = (s + 1) + "." + String.format("%03d", 999 - i);
                points.add(point(UUID.randomUUID(), v, LocalDate.parse("2026-09-30").minusDays(i + s)));
            }
            newest.put("Marker" + s, points.get(0).value());
            five.add(series("Marker" + s, "mg/dL", points));
        }
        labs(ASKED, five);
        noPrescription(ASKED);

        ChatContext ctx = builder(800).build(FAMILY, ASKED, "Ma", BN).block();

        assertThat(ChatContext.estimateTokens(String.join("\n", ctx.factLines()))).as("the context's estimate").isLessThanOrEqualTo(800);
        List<String> kept = values(ctx);
        assertThat(kept).as("every series' newest reading is kept").containsAll(newest.values());
        for (LabSeries s : five) {
            List<String> all = s.points().stream().map(LabSeries.Point::value).toList();
            List<String> keptHere = all.stream().filter(kept::contains).toList();
            assertThat(keptHere).as(s.name() + ": the kept readings are its newest ones, the oldest dropped first")
                    .isEqualTo(all.subList(0, keptHere.size()));
        }
        int dropped = 200 - kept.size();
        assertThat(dropped).as("the cap forced drops").isGreaterThan(0);
        assertThat(ctx.factLines()).contains("NOT SHOWN " + dropped);
        assertThat(ctx.truncated()).isFalse();
    }

    @Test
    void thePrescriptionIsNeverDropped() {
        List<LabSeries.Point> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            points.add(point(UUID.randomUUID(), "1." + i, LocalDate.parse("2026-09-30").minusDays(i)));
        }
        labs(ASKED, List.of(series("Creatinine", "mg/dL", points)));
        UUID rx = UUID.randomUUID();
        Map<String, Object> med = medicine();
        when(health.medicines(FAMILY, ASKED)).thenReturn(Mono.just(new LatestPrescription(rx, LocalDate.parse("2026-09-20"), false, false,
                List.of(med), Optional.empty())));

        ChatContext ctx = builder(400).build(FAMILY, ASKED, "Ma", BN).block();

        assertThat(values(ctx)).hasSizeLessThan(100);
        assertThat(ctx.factLines()).contains("PRESCRIPTION 2026-09-20", "{{rx:1}} = " + MedicineTranscription.line(med));
        assertThat(ctx.documentIds()).contains(rx);
    }

    @Test
    void theClosedSetIsTheKeptFieldsOnly() {
        List<LabSeries.Point> points = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            points.add(point(UUID.randomUUID(), String.valueOf(100 + i), LocalDate.parse("2026-09-30")));
        }
        labs(ASKED, List.of(series("Glucose", "mg/dL", points)));
        noPrescription(ASKED);

        ChatContext ctx = builder(300).build(FAMILY, ASKED, "Ma", BN).block();

        List<String> kept = values(ctx);
        String droppedValue = points.get(points.size() - 1).value();
        assertThat(kept).doesNotContain(droppedValue);
        assertThat(ctx.numbers()).as("a dropped reading's value").doesNotContain(droppedValue);
        assertThat(ctx.numbers()).containsAll(kept);
    }

    @Test
    void theRxLineIsTheStoredLineVerbatim() {
        labs(ASKED, List.of());
        Map<String, Object> med = medicine();
        when(health.medicines(FAMILY, ASKED)).thenReturn(Mono.just(new LatestPrescription(UUID.randomUUID(), LocalDate.parse("2026-09-20"),
                false, false, List.of(med), Optional.empty())));

        ChatContext ctx = builder(6000).build(FAMILY, ASKED, "Ma", BN).block();

        assertThat(ctx.verbatimLines()).containsExactly(MedicineTranscription.line(med));
        assertThat(ctx.verbatimLines().get(0)).isEqualTo("Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে · ৩০ দিন");
    }

    /**
     * A context with every label kind in it: an OUTSIDE high reading, an INSIDE one, a CRITICAL low one, an UNKNOWN one with
     * no printed range and no date, a blood-pressure pair, and an undated prescription.
     */
    private ChatContext everyLabel(ChatLabels labels) {
        LocalDate day = LocalDate.parse("2026-09-12");
        List<LabSeries> series = List.of(
                series("Creatinine", "mg/dL", List.of(point(UUID.randomUUID(), "1.8", day))),
                series("Potassium", "mmol/L", List.of(new LabSeries.Point(UUID.randomUUID(), UUID.randomUUID(), "4.1", day, false, false,
                        ValueVerdict.Kind.INSIDE, null, "k", PrintedBand.of("3.5", "5.1")))),
                series("Sodium", "mmol/L", List.of(new LabSeries.Point(UUID.randomUUID(), UUID.randomUUID(), "118", day, false, false,
                        ValueVerdict.Kind.CRITICAL, "low", "k", PrintedBand.of("135", "145")))),
                series("Urine sugar", "", List.of(new LabSeries.Point(UUID.randomUUID(), UUID.randomUUID(), "2+", null, false, false,
                        ValueVerdict.Kind.UNKNOWN, null, "k", PrintedBand.NONE))));
        LabSeries.Point sys = new LabSeries.Point(UUID.randomUUID(), UUID.randomUUID(), "150", day, false, false, ValueVerdict.Kind.OUTSIDE,
                "high", "k", PrintedBand.of("90", "140"));
        LabSeries.Point dia = new LabSeries.Point(UUID.randomUUID(), UUID.randomUUID(), "80", day, false, false, ValueVerdict.Kind.INSIDE,
                null, "k", PrintedBand.of("60", "90"));
        LabSeries.BpSeries bp = new LabSeries.BpSeries(List.of(new LabSeries.BpPoint(UUID.randomUUID(), day, false, false, sys, dia, "line")));
        when(health.labs(FAMILY, ASKED)).thenReturn(Mono.just(new HealthUseCase.HealthLabs(series, bp)));
        when(health.medicines(FAMILY, ASKED)).thenReturn(Mono.just(new LatestPrescription(UUID.randomUUID(), null, false, false,
                List.of(medicine()), Optional.empty())));
        return builder(6000).build(FAMILY, ASKED, "Ma", labels).block();
    }

    /** The PRESCRIPTION line's date field: the line without its tag. */
    static String prescriptionDate(ChatContext ctx) {
        List<String> rx = ctx.factLines().stream().filter(l -> l.startsWith("PRESCRIPTION ")).toList();
        assertThat(rx).as("one PRESCRIPTION line").hasSize(1);
        return rx.get(0).substring("PRESCRIPTION ".length());
    }

    static ValueLine named(List<ValueLine> lines, String name) {
        return lines.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no VALUE line for " + name));
    }

    // the live Sonnet test of #164: two Bangla answers copied the context's English labels ("printed range", "OUTSIDE high")
    @Test
    void aBanglaAsksContextLabelsEveryFieldInBangla() {
        ChatContext ctx = everyLabel(BN);

        List<ValueLine> lines = valueLines(ctx);
        assertThat(lines).as("five readings, the pair as one line").hasSize(5);
        for (ValueLine v : lines) {
            // per label field, never a substring of the whole prompt: a value's printed name and unit may well be English
            assertThat(v.range()).as("the range field of a Bangla ask: " + v.line()).doesNotContainPattern(LATIN);
            assertThat(v.verdict()).as("the verdict field of a Bangla ask: " + v.line()).doesNotContainPattern(LATIN);
            assertThat(v.paper()).as("the paper field of a Bangla ask: " + v.line()).doesNotContainPattern(LATIN);
        }
        assertThat(prescriptionDate(ctx)).as("the undated prescription's date field").doesNotContainPattern(LATIN);

        // and each field is the paper page's own copy, read from the bundle, never words typed here
        ValueLine creatinine = named(lines, "Creatinine");
        assertThat(creatinine.range()).isEqualTo(COPY.line("bn", "value.range.one", "0.7–1.3"));
        assertThat(creatinine.verdict()).isEqualTo(ChatLabels.withoutArrow(COPY.line("bn", "value.chip.outside.high")));
        assertThat(creatinine.paper()).isEqualTo(COPY.line("bn", "timeline.paper") + " 2026-09-12");
        ValueLine urine = named(lines, "Urine sugar");
        assertThat(urine.range()).isEqualTo(COPY.line("bn", "value.range.none"));
        assertThat(urine.verdict()).isEqualTo(COPY.line("bn", "value.chip.unknown"));
        assertThat(urine.paper()).isEqualTo(COPY.line("bn", "timeline.sum.nodate"));
        assertThat(named(lines, "Sodium").verdict()).isEqualTo(COPY.line("bn", "value.chip.critical.low"));
        assertThat(named(lines, "Potassium").verdict()).isEqualTo(COPY.line("bn", "value.chip.inside"));
        assertThat(prescriptionDate(ctx)).isEqualTo(COPY.line("bn", "timeline.sum.nodate"));

        // the checks' own records keep the canonical verdict: "inside" is still backed only by an INSIDE reading
        assertThat(ctx.facts()).extracting(ChatContext.Fact::verdict).contains("OUTSIDE high", "INSIDE", "OUTSIDE low", "UNKNOWN");
        assertThat(ctx.foreignLabels()).as("what a Bangla answer may not hold").containsAll(ChatLabels.TAGS)
                .contains("printed range", ChatLabels.withoutArrow(COPY.line("en", "value.chip.outside.high")));
    }

    @Test
    void anEnglishAskStillGetsEnglishLabels() {
        ChatContext ctx = everyLabel(EN);

        List<ValueLine> lines = valueLines(ctx);
        assertThat(lines).hasSize(5);
        for (ValueLine v : lines) {
            assertThat(v.range()).as("the range field of an English ask: " + v.line()).doesNotContainPattern(BENGALI);
            assertThat(v.verdict()).as("the verdict field of an English ask: " + v.line()).doesNotContainPattern(BENGALI)
                    .containsPattern(LATIN);
            assertThat(v.paper()).as("the paper field of an English ask: " + v.line()).doesNotContainPattern(BENGALI);
        }
        ValueLine creatinine = named(lines, "Creatinine");
        assertThat(creatinine.range()).isEqualTo(COPY.line("en", "value.range.one", "0.7–1.3"));
        assertThat(creatinine.verdict()).isEqualTo(ChatLabels.withoutArrow(COPY.line("en", "value.chip.outside.high")));
        assertThat(creatinine.paper()).isEqualTo(COPY.line("en", "timeline.paper") + " 2026-09-12");
        assertThat(ctx.foreignLabels()).as("an English word is not foreign in an English answer").isEmpty();
    }

    /** How many VALUE lines a context keeps under the shipped cap, from 400 series of one reading or one series of 400. */
    static int keptUnderTheShippedCap(ChatLabels labels, boolean oneSeries) {
        int cap = new BaymaxProperties().getChat().getContextMaxTokens();
        List<LabSeries> all = new ArrayList<>();
        List<LabSeries.Point> history = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            LabSeries.Point p = point(UUID.randomUUID(), "1." + String.format("%03d", i), LocalDate.parse("2026-09-30").minusDays(i));
            if (oneSeries) {
                history.add(p);
            } else {
                all.add(series("Marker" + i, "mg/dL", List.of(p)));
            }
        }
        if (oneSeries) {
            all.add(series("Creatinine", "mg/dL", history));
        }
        ChatContext ctx = assemble(all, cap, labels);
        assertThat(ChatContext.estimateTokens(String.join("\n", ctx.factLines()))).isLessThanOrEqualTo(cap);
        return valueLines(ctx).size();
    }

    static ChatContext assemble(List<LabSeries> series, int cap, ChatLabels labels) {
        return ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(series, new LabSeries.BpSeries(List.of())),
                LatestPrescription.none(Optional.empty()), cap, labels);
    }

    /**
     * Review of #170, finding 12, measured rather than claimed: Bangla labels make each VALUE line longer in
     * {@link ChatContext#estimateTokens}, which counts a non-ASCII character as a token, so under the shipped cap a Bangla ask
     * keeps fewer series whole (the rest are dropped, oldest series first, and the context is truncated) and fewer older
     * readings of one series. The numbers are in the PR body; the bound here says Bangla keeps fewer, but not under a third.
     */
    @Test
    void aBanglaAskKeepsFewerReadingsUnderTheShippedCapAndTheCountIsMeasured() {
        int bnSeries = keptUnderTheShippedCap(BN, false);
        int enSeries = keptUnderTheShippedCap(EN, false);
        int bnHistory = keptUnderTheShippedCap(BN, true);
        int enHistory = keptUnderTheShippedCap(EN, true);
        String measured = "single-reading series kept: bn " + bnSeries + ", en " + enSeries + "; readings of one series kept: bn "
                + bnHistory + ", en " + enHistory;
        assertThat(bnSeries).as(measured).isLessThan(enSeries).isGreaterThan(enSeries / 3);
        assertThat(bnHistory).as(measured).isLessThan(enHistory).isGreaterThan(enHistory / 3);
        assertThat(assemble(List.of(series("Marker", "mg/dL", List.of(point(UUID.randomUUID(), "1.8", LocalDate.parse("2026-09-30"))))),
                new BaymaxProperties().getChat().getContextMaxTokens(), BN).truncated()).as("one series is never truncated").isFalse();
    }

    static Map<String, Object> medicine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Tab. Demoprol 5 mg");
        m.put("dose_text", "1+0+0");
        m.put("timing_text", "খাবারের পরে");
        m.put("duration_text", "৩০ দিন");
        m.put("crop_key", "k");
        return m;
    }
}
