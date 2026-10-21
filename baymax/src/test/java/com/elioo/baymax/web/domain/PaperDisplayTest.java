package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static com.elioo.baymax.testsupport.PaperFixtures.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Rebuild plan B2 step 1: the display rules the HTML pages and {@code /app/api} both read, in the domain where neither
 * adapter owns them. Each rule is asserted in the direction that withholds reassurance AND in the direction that keeps
 * a proven value proven, so a rule that turned everything UNKNOWN would fail here too.
 */
class PaperDisplayTest {

    @Test
    void aSidelessOutsideOrCriticalIsUnknown() {
        assertThat(PaperDisplay.kindOf(value("K", "6.1", "mmol/L", "3.5", "5.1", "outside", null))).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(PaperDisplay.kindOf(value("K", "6.1", "mmol/L", "3.5", "5.1", "critical", null))).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        // the other direction: with its stored side, the stored verdict is shown as stored
        assertThat(PaperDisplay.kindOf(value("K", "6.1", "mmol/L", "3.5", "5.1", "outside", "high"))).isEqualTo(ValueVerdict.Kind.OUTSIDE);
        assertThat(PaperDisplay.kindOf(value("K", "6.9", "mmol/L", "3.5", "5.1", "critical", "high"))).isEqualTo(ValueVerdict.Kind.CRITICAL);
    }

    @Test
    void aOneSidedInsideIsUnknown() {
        assertThat(PaperDisplay.kindOf(value("LDL", "90", "mg/dL", null, "100", "inside", null))).isEqualTo(ValueVerdict.Kind.UNKNOWN);
    }

    @Test
    void anInsideTheCodeProvesStaysInside() {
        Map<String, Object> row = value("Na", "140", "mmol/L", "135", "145", "inside", null);
        assertThat(PaperDisplay.kindOf(row)).isEqualTo(ValueVerdict.Kind.INSIDE);
        OptionalDouble pin = PaperDisplay.pin(ValueVerdict.Kind.INSIDE, row, null);
        assertThat(pin).isPresent();
        assertThat(pin.getAsDouble()).isBetween(25.0, 75.0);
    }

    @Test
    void aRowWithNoStoredVerdictIsUnknownNotAnError() {
        Map<String, Object> none = value("Na", "140", "mmol/L", "135", "145", null, null);
        Map<String, Object> bogus = value("Na", "140", "mmol/L", "135", "145", "bogus", null);
        assertThatCode(() -> PaperDisplay.kindOf(none)).doesNotThrowAnyException();
        assertThatCode(() -> PaperDisplay.kindOf(bogus)).doesNotThrowAnyException();
        assertThat(PaperDisplay.kindOf(none)).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(PaperDisplay.kindOf(bogus)).isEqualTo(ValueVerdict.Kind.UNKNOWN);
    }

    @Test
    void nowReasonsComeFirstThenTheOthersInStoredOrder() {
        List<Optional<UrgencyReason>> drawn = PaperDisplay.drawnOrder(List.of("value_outside_range|stopgap", "value_critical|threshold:x", "legacy_thing"));
        assertThat(drawn.stream().map(r -> r.map(UrgencyReason::prefix).orElse("<empty>")).toList())
                .containsExactly("value_critical", "<empty>", "value_outside_range");
        assertThat(PaperDisplay.drawnOrder(List.of())).containsExactly(Optional.empty());
    }

    @Test
    void onlyANowPaperDrawsValueRowsAboveAndOnlyForAValueCriticalReason() {
        List<String> critical = List.of("value_critical:potassium|threshold:x");
        assertThat(PaperDisplay.shownAbove(Urgency.NOW, critical)).containsExactly(ValueVerdict.Kind.CRITICAL);
        assertThat(PaperDisplay.shownAbove(Urgency.NOW, List.of("text_emergency_phrase"))).isEmpty();
        for (Urgency below : Arrays.asList(Urgency.THIS_WEEK, Urgency.ROUTINE, null)) {
            assertThat(PaperDisplay.shownAbove(below, critical)).as(String.valueOf(below)).isEmpty();
        }
    }

    @Test
    void theOutsideCountCountsTheRenderedKindNeverTheStoredOne() {
        List<Map<String, Object>> rows = List.of(
                value("K", "6.9", "mmol/L", "3.5", "5.1", "critical", "high"),
                value("Na", "128", "mmol/L", "135", "145", "outside", "low"),
                value("Cl", "120", "mmol/L", "98", "107", "outside", null),       // sideless: drawn UNKNOWN, not counted
                value("Hb", "13", "g/dL", "12", "15.5", "inside", null));
        assertThat(PaperDisplay.outsideCount(rows)).isEqualTo(2);
        assertThat(PaperDisplay.outsideCount(List.of())).isZero();
    }
}
