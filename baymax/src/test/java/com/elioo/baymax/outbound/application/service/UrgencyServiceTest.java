package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Urgency from printed ranges and document text only; never from the model's flag. */
class UrgencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private final UrgencyService service = new UrgencyService(new BaymaxProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    static Map<String, Object> value(String name, String v, String low, String high) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name); m.put("value", v);
        if (low != null) { m.put("ref_low", low); m.put("ref_high", high); }
        return m;
    }

    static DocumentFacts facts(List<Map<String, Object>> values, List<Map<String, Object>> followUps, Map<String, Object> ctx, String json) {
        return facts("lab_report", values, followUps, ctx, json);
    }

    static DocumentFacts facts(String type, List<Map<String, Object>> values, List<Map<String, Object>> followUps, Map<String, Object> ctx, String json) {
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, null, null, json, 0.9,
                Document.Status.DONE, null, "m", null, 1, NOW, NOW);
        return new DocumentFacts(d, "Ma", values, followUps, ctx, "https://x/app/documents/1");
    }

    /** Acceptance (PO ruling 2026-09-18, STOPGAP): ≥ 2.0 × ref_high → NOW. Range 4.0–5.6: 11.2 is exactly 2 × 5.6. */
    @Test
    void aValueAtTwiceTheUpperLimitIsNow() {
        var a = service.assess(facts(List.of(value("HbA1c", "11.2", "4.0", "5.6")), List.of(), Map.of(), "{}"));
        assertThat(a.level()).isEqualTo(Urgency.NOW);
        assertThat(a.reasons()).contains("value_critical:hba1c");
    }

    /** ≤ 0.5 × ref_low → NOW. Range 4.0–5.6: 2.0 is exactly half of 4.0. */
    @Test
    void aValueAtHalfTheLowerLimitIsNow() {
        assertThat(service.assess(facts(List.of(value("Hb", "2.0", "4.0", "5.6")), List.of(), Map.of(), "{}")).level()).isEqualTo(Urgency.NOW);
        assertThat(service.assess(facts(List.of(value("Hb", "2.1", "4.0", "5.6")), List.of(), Map.of(), "{}")).level()).isEqualTo(Urgency.THIS_WEEK);
    }

    /** Outside the range but under the multiples — even well outside — stays THIS_WEEK. 9.8 was NOW under the old width rule. */
    @Test
    void outsideTheRangeButUnderTheMultiplesIsThisWeek() {
        assertThat(service.assess(facts(List.of(value("HbA1c", "9.8", "4.0", "5.6")), List.of(), Map.of(), "{}")).level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(service.assess(facts(List.of(value("HbA1c", "6.5", "4.0", "5.6")), List.of(), Map.of(), "{}")).level()).isEqualTo(Urgency.THIS_WEEK);
    }

    /** The page's own marking near the value makes an outside-range value NOW, in either script; far from it, it does not. */
    @Test
    void thePagesOwnCriticalMarkingNearTheValueIsNow() {
        String near = "{\"values\":[{\"name\":\"HbA1c\",\"value\":\"6.5\",\"flag\":\"high\"}],\"free_text_summary\":\"HbA1c 6.5 marked critical\"}";
        assertThat(service.assess(facts(List.of(value("HbA1c", "6.5", "4.0", "5.6")), List.of(), Map.of(), near)).level()).isEqualTo(Urgency.NOW);
        String nearBn = "{\"advice\":\"HbA1c 6.5 খুব বেশি\"}";
        assertThat(service.assess(facts(List.of(value("HbA1c", "6.5", "4.0", "5.6")), List.of(), Map.of(), nearBn)).level()).isEqualTo(Urgency.NOW);
        String far = "{\"a\":\"6.5\",\"b\":\"" + "x ".repeat(80) + "critical\"}";
        assertThat(service.assess(facts(List.of(value("HbA1c", "6.5", "4.0", "5.6")), List.of(), Map.of(), far)).level()).isEqualTo(Urgency.THIS_WEEK);
        // a marking with no printed range still escalates nothing: no range, no escalation from values
        assertThat(service.assess(facts(List.of(value("HbA1c", "6.5", null, null)), List.of(), Map.of(), near)).level()).isEqualTo(Urgency.ROUTINE);
    }

    /** Acceptance: no printed range anywhere → never above ROUTINE from values, whatever the number. */
    @Test
    void withoutAPrintedRangeValuesNeverEscalate() {
        var a = service.assess(facts(List.of(value("HbA1c", "14.0", null, null), value("BP", "220", null, null)), List.of(), Map.of(), "{}"));
        assertThat(a.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void theModelsFlagIsIgnored() {
        Map<String, Object> v = value("HbA1c", "5.0", "4.0", "5.6");
        v.put("flag", "critical");
        assertThat(service.assess(facts(List.of(v), List.of(), Map.of(), "{}")).level()).isEqualTo(Urgency.ROUTINE);
    }

    /** A diagnosis line escalates a lab report or discharge summary, never a prescription (PO ruling 2026-09-18). */
    @Test
    void aDiagnosisLineEscalatesOnlyWhereADoctorHasNotJustBeenSeen() {
        Map<String, Object> dx = Map.of("diagnosis", List.of(Map.of("text", "x")));
        assertThat(service.assess(facts("lab_report", List.of(), List.of(), dx, "{}")).level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(service.assess(facts("discharge_summary", List.of(), List.of(), dx, "{}")).level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(service.assess(facts("prescription", List.of(), List.of(), dx, "{}")).level()).isEqualTo(Urgency.ROUTINE);
        assertThat(service.assess(facts("prescription", List.of(), List.of(), dx, "{}")).reasons()).doesNotContain("diagnosis_present");
    }

    @Test
    void aDiagnosisLineOrAPastDueFollowUpIsThisWeek() {
        assertThat(service.assess(facts(List.of(), List.of(), Map.of("diagnosis", List.of(Map.of("text", "x"))), "{}")).level())
                .isEqualTo(Urgency.THIS_WEEK);
        assertThat(service.assess(facts(List.of(), List.of(Map.of("instruction", "i", "due_date", "2026-09-01")), Map.of(), "{}")).level())
                .isEqualTo(Urgency.THIS_WEEK);
        assertThat(service.assess(facts(List.of(), List.of(Map.of("instruction", "i", "due_date", "2026-12-01")), Map.of(), "{}")).level())
                .isEqualTo(Urgency.ROUTINE);
    }

    @Test
    void aVerbatimEmergencyPhraseInTheDocumentTextIsNowInEitherScript() {
        assertThat(service.assess(facts(List.of(), List.of(), Map.of(), "{\"advice\":\"admit immediately\"}")).level()).isEqualTo(Urgency.NOW);
        assertThat(service.assess(facts(List.of(), List.of(), Map.of(), "{\"advice\":\"জরুরি ভিত্তিতে\"}")).level()).isEqualTo(Urgency.NOW);
        assertThat(service.assess(facts(List.of(), List.of(), Map.of(), "{\"advice\":\"insurgent\"}")).level()).isEqualTo(Urgency.ROUTINE);
    }

    @Test
    void banglaDigitsInAValueAreRead() {
        var a = service.assess(facts(List.of(value("HbA1c", "১১.২", "4.0", "5.6")), List.of(), Map.of(), "{}"));
        assertThat(a.level()).isEqualTo(Urgency.NOW);
    }

    /** The assessment type only raises: no combination of raise() calls can end lower than any input. */
    @Test
    void urgencyIsNeverLowered() {
        var a = com.elioo.baymax.outbound.domain.UrgencyAssessment.routine().raise(Urgency.NOW, "a").raise(Urgency.THIS_WEEK, "b").raise(Urgency.ROUTINE, "c");
        assertThat(a.level()).isEqualTo(Urgency.NOW);
        assertThat(a.reasons()).containsExactly("a", "b", "c");
    }
}
