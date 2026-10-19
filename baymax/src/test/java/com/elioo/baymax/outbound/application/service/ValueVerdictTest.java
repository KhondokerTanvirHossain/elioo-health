package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F2: a row's verdict comes from the branch that set the banner, and "inside" must be proven by code. */
class ValueVerdictTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService service = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    static Map<String, Object> obs(String name, String value, String low, String high, String unit) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", UUID.randomUUID());
        m.put("name", name);
        m.put("canonical_name", name);
        m.put("value", value);
        m.put("unit", unit);
        if (low != null) { m.put("ref_low", low); }
        if (high != null) { m.put("ref_high", high); }
        return m;
    }

    private ValueVerdict only(Map<String, Object> o) {
        return service.assess(UrgencyServiceTest.facts(List.of(o), List.of(), Map.of(), "{}")).values().get(0);
    }

    /** Spec F2 (b): every one of these is UNKNOWN — never INSIDE, never teal. */
    @ParameterizedTest(name = "''{0}'' against ''{1}''–''{2}'' is UNKNOWN")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "> 89     | 60   | 120",
            "130 / 80 | 90   | 140",
            "1,23     | 0.5  | 1.5",
            "Positive | null | null",
            "250      | null | 100",
            "250      | null | <100",
            "250      | null | null",
            "5.0      | 6.0  | 4.0"})     // inverted printed range
    void aValueCodeCannotPlaceIsUnknownNeverInside(String value, String low, String high) {
        ValueVerdict v = only(obs("ldl", value, low, high, "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(v.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(v.reason()).startsWith("value_unknown:ldl|");
    }

    /** The other direction: a parsed value between two parsed bounds IS inside. */
    @Test
    void aParsedValueBetweenParsedBoundsIsInside() {
        ValueVerdict v = only(obs("creatinine", "1.0", "0.7", "1.3", "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.INSIDE);
        assertThat(v.side()).isNull();
        assertThat(v.reason()).isEqualTo("value_inside:creatinine|stopgap");
    }

    /**
     * Assert the path, not the outcome (CLAUDE.md "when two rules can produce the same verdict"): every raising row's
     * reason is one of the document's reasons, and a threshold row names its source.
     */
    @Test
    void everyRaisingRowsReasonIsADocumentReasonAndAThresholdRowSaysWhichRule() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("potassium", "mmol/L", 2.5, 6.0, 3.5, 5.1,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "test-signed"));
        Map<String, Object> potassium = obs("potassium", "6.4", "3.5", "5.1", "mmol/L");   // stopgap alone: 1.25x → OUTSIDE
        Map<String, Object> hba1c = obs("hba1c", "6.5", "4.0", "5.6", "%");

        UrgencyAssessment a = service.assess(UrgencyServiceTest.facts(List.of(potassium, hba1c), List.of(), Map.of(), "{}"));

        assertThat(a.values()).filteredOn(v -> v.level() != Urgency.ROUTINE).hasSize(2)
                .allSatisfy(v -> assertThat(a.reasons()).contains(v.reason()));
        assertThat(verdictOf(a, potassium)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.CRITICAL);
            assertThat(v.side()).isEqualTo(ValueVerdict.Side.HIGH);
            assertThat(v.reason()).isEqualTo("value_critical:potassium|threshold:test-signed");
        });
        assertThat(verdictOf(a, hba1c)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
            assertThat(v.side()).isEqualTo(ValueVerdict.Side.HIGH);
            assertThat(v.reason()).isEqualTo("value_outside_range:hba1c|stopgap");
        });
    }

    /** The low end, by threshold and by stopgap: a row below its range says LOW, from the same branch that raised. */
    @Test
    void aValueBelowItsRangeIsLowOnBothPaths() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("potassium", "mmol/L", 2.5, 6.0, 3.0, 5.5,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "test-signed"));
        Map<String, Object> potassium = obs("potassium", "2.4", "3.5", "5.1", "mmol/L");
        Map<String, Object> hb = obs("hb", "5.0", "12.0", "16.0", "g/dL");               // ≤ 0.5 × 12 → stopgap CRITICAL_LOW

        UrgencyAssessment a = service.assess(UrgencyServiceTest.facts(List.of(potassium, hb), List.of(), Map.of(), "{}"));

        assertThat(verdictOf(a, potassium)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.CRITICAL);
            assertThat(v.side()).isEqualTo(ValueVerdict.Side.LOW);
        });
        assertThat(verdictOf(a, hb)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.CRITICAL);
            assertThat(v.side()).isEqualTo(ValueVerdict.Side.LOW);
            assertThat(v.reason()).isEqualTo("value_critical:hb|stopgap");
        });
    }

    /** DR-17: a never-escalating marker outside its printed range is OUTSIDE at ROUTINE, never INSIDE. */
    @Test
    void aNeverEscalatingMarkerOutsideItsPrintedRangeIsOutsideNotInside() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("ldl", "mg/dL", null, null, null, null,
                true, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "DR-17"));
        ValueVerdict v = only(obs("ldl", "250", "0", "100", "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
        assertThat(v.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(v.side()).isEqualTo(ValueVerdict.Side.HIGH);
        assertThat(v.reason()).isEqualTo("value_outside_no_escalation:ldl|threshold:DR-17");
    }

    /** DR-28: an unverified value raises the document and emits no row — there is no observation to hang it on. */
    @Test
    void anUnverifiedValueRaisesButEmitsNoRow() {
        Map<String, Object> unverified = obs("potassium", "6.1", "3.5", "5.1", "mmol/L");
        unverified.remove("id");
        var facts = UrgencyServiceTest.factsWithUnverified(List.of(), List.of(unverified));   // UnverifiedValuesTest's fixture shape
        UrgencyAssessment a = service.assess(facts);
        assertThat(a.level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(a.reasons()).containsExactly("unverified_value_outside_range:potassium");
        assertThat(a.values()).isEmpty();
    }

    /** F2 (c): "within the normal range" holds exactly when every verdict is INSIDE — over every WithinNormalRangeClaimTest fixture. */
    @Test
    void theNormalRangeClaimHoldsOnlyWhenEveryVerdictIsInside() {
        List<WithinNormalRangeClaimTest.Fixture> fixtures = WithinNormalRangeClaimTest.fixtures();
        assertThat(fixtures).as("the claim's own fixtures").hasSizeGreaterThanOrEqualTo(8);
        assertThat(fixtures).as("both directions are present").anySatisfy(f -> assertThat(f.name()).contains("all inside"))
                .anySatisfy(f -> assertThat(f.name()).contains("LDL"));
        int claimed = 0;
        for (WithinNormalRangeClaimTest.Fixture f : fixtures) {
            UrgencyAssessment a = service.assess(f.facts());
            boolean allInside = !a.values().isEmpty() && f.facts().document().unverified().total() == 0
                    && a.values().stream().allMatch(v -> v.kind() == ValueVerdict.Kind.INSIDE) && !f.modelFlagged();
            assertThat(ExplanationService.canSayWithinNormalRange(f.facts(), a)).as(f.name()).isEqualTo(allInside);
            claimed += allInside ? 1 : 0;
        }
        assertThat(claimed).as("the protected direction is exercised, not only refusals").isGreaterThanOrEqualTo(2);
    }

    /** The verdict wins over the code's range check: a value inside its printed range a live threshold raises is no reassurance. */
    @Test
    void aValueInsideItsPrintedRangeThatAThresholdRaisesWithdrawsTheClaim() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("potassium", "mmol/L", 2.5, 6.0, 3.0, 5.0,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "test-signed"));
        Map<String, Object> potassium = obs("potassium", "5.05", "3.5", "5.1", "mmol/L");   // inside 3.5–5.1, above weekHigh 5.0
        DocumentFacts facts = UrgencyServiceTest.facts(List.of(potassium), List.of(), Map.of(), "{}");
        UrgencyAssessment a = service.assess(facts);
        assertThat(verdictOf(a, potassium).kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
        assertThat(ExplanationService.canSayWithinNormalRange(facts)).as("the printed range alone would claim it").isTrue();
        assertThat(ExplanationService.canSayWithinNormalRange(facts, a)).isFalse();
    }

    /** UrgencyReason: a raise with a prefix the enum does not name is refused, so B3's derived blocks cannot miss one. */
    @Test
    void aReasonMustCarryAKnownPrefix() {
        assertThatThrownBy(() -> UrgencyAssessment.routine().raise(Urgency.NOW, "made_up_reason:x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("made_up_reason");
        assertThat(UrgencyAssessment.routine().raise(Urgency.NOW, "value_critical:k|stopgap").reasons()).containsExactly("value_critical:k|stopgap");
    }

    private static ValueVerdict verdictOf(UrgencyAssessment a, Map<String, Object> o) {
        return a.values().stream().filter(v -> v.observationId().equals(o.get("id"))).findFirst().orElseThrow();
    }
}
