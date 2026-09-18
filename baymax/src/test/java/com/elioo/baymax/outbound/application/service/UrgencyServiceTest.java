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
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lab_report", null, null, json, 0.9,
                Document.Status.DONE, null, "m", null, 1, NOW, NOW);
        return new DocumentFacts(d, "Ma", values, followUps, ctx, "https://x/baymax/documents/1");
    }

    /** Acceptance: a value in a critical band → NOW. Range 4.0–5.6, width 1.6; 9.8 is more than one width above. */
    @Test
    void aValueInTheCriticalBandIsNow() {
        var a = service.assess(facts(List.of(value("HbA1c", "9.8", "4.0", "5.6")), List.of(), Map.of(), "{}"));
        assertThat(a.level()).isEqualTo(Urgency.NOW);
        assertThat(a.reasons()).contains("value_critical:hba1c");
    }

    @Test
    void aValueOutsideTheRangeButInsideTheBandIsThisWeek() {
        var a = service.assess(facts(List.of(value("HbA1c", "6.5", "4.0", "5.6")), List.of(), Map.of(), "{}"));
        assertThat(a.level()).isEqualTo(Urgency.THIS_WEEK);
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
        var a = service.assess(facts(List.of(value("HbA1c", "৯.৮", "4.0", "5.6")), List.of(), Map.of(), "{}"));
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
