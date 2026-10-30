package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.MedicineTranscription;
import com.elioo.baymax.outbound.domain.ValueVerdict;
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
    static final Pattern VALUE = Pattern.compile("^VALUE (.+): (\\S+)(?: (.*))? \\| printed range .* \\| .* \\| paper (\\d{4}-\\d{2}-\\d{2})$");

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

        ChatContext ctx = builder(6000).build(FAMILY, ASKED, "Ma").block();

        assertThat(values(ctx)).containsExactly("1.8");
        assertThat(ctx.numbers()).contains("1.8", "0.7", "1.3", "2026", "9", "12").doesNotContain("7.77");
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

        ChatContext ctx = builder(800).build(FAMILY, ASKED, "Ma").block();

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
        assertThat(ctx.factLines()).contains("NOT SHOWN " + dropped + " older readings");
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

        ChatContext ctx = builder(400).build(FAMILY, ASKED, "Ma").block();

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

        ChatContext ctx = builder(300).build(FAMILY, ASKED, "Ma").block();

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

        ChatContext ctx = builder(6000).build(FAMILY, ASKED, "Ma").block();

        assertThat(ctx.verbatimLines()).containsExactly(MedicineTranscription.line(med));
        assertThat(ctx.verbatimLines().get(0)).isEqualTo("Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে · ৩০ দিন");
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
