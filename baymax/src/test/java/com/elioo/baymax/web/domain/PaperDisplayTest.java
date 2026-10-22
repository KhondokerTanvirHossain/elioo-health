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

    // ---- R3 (DR-48): the printed band a trend point may draw agrees with its chip, as the pin does -------------------

    private static PrintedBand drawn(Map<String, Object> row) {
        ValueVerdict.Kind kind = PaperDisplay.kindOf(row);
        return PaperDisplay.drawnBand(kind, row, PaperDisplay.drawnSide(kind, row));
    }

    /** The direction that keeps a proven picture: a band that agrees with the chip is drawn with both numbers. */
    @Test
    void aBandThatAgreesWithTheChipIsDrawn() {
        assertThat(drawn(value("Na", "140", "mmol/L", "135", "145", "inside", null))).isEqualTo(new PrintedBand(135.0, 145.0, "135–145"));
        assertThat(drawn(value("Creatinine", "132", "umol/L", "59", "104", "outside", "high"))).isEqualTo(new PrintedBand(59.0, 104.0, "59–104"));
        assertThat(drawn(value("K", "2.4", "mmol/L", "3.5", "5.1", "critical", "low"))).isEqualTo(new PrintedBand(3.5, 5.1, "3.5–5.1"));
    }

    /**
     * The direction that withholds: a band that would tell a different story from the chip sends no number, only the
     * text. A CRITICAL by a signed-off threshold (which never looks at the printed range) inside its printed band would
     * put a red point inside the pale "normal"; an UNKNOWN chip on a point inside the band reads as the reassurance the
     * verdict withheld; an OUTSIDE high under the band contradicts its side.
     */
    @Test
    void aBandThatContradictsTheChipIsTextOnly() {
        assertThat(drawn(value("Creatinine", "90", "umol/L", "59", "104", "critical", "high")))
                .as("CRITICAL by threshold inside the printed band").isEqualTo(new PrintedBand(null, null, "59–104"));
        assertThat(drawn(value("Creatinine", "90", "umol/L", "59", "104", null, null)))
                .as("a NULL verdict inside the band").isEqualTo(new PrintedBand(null, null, "59–104"));
        assertThat(drawn(value("Creatinine", "50", "umol/L", "59", "104", "outside", "high")))
                .as("OUTSIDE high below the band").isEqualTo(new PrintedBand(null, null, "59–104"));
        assertThat(drawn(value("Na", "150", "mmol/L", "135", "145", "outside", null)))
                .as("a sideless OUTSIDE is UNKNOWN").isEqualTo(new PrintedBand(null, null, "135–145"));
    }

    /**
     * A one-sided bound is never INSIDE (D35), so an UNKNOWN point beside it gets no number: an open region from the axis
     * to 200 behind 180 would say "normal" where the chip says unknown. Only an OUTSIDE or CRITICAL beyond the bound on
     * its own side keeps it, as an edge.
     */
    @Test
    void aOneSidedBoundIsKeptOnlyBesideAPointBeyondItOnTheChipsSide() {
        assertThat(drawn(value("Total Cholesterol", "180", "mg/dL", null, "<200", "inside", null)))
                .as("a one-sided INSIDE is UNKNOWN").isEqualTo(new PrintedBand(null, null, "<200"));
        assertThat(drawn(value("Total Cholesterol", "210", "mg/dL", null, "<200", null, null)))
                .as("the stopgap stores UNKNOWN above a one-sided bound").isEqualTo(new PrintedBand(null, null, "<200"));
        assertThat(drawn(value("HDL", "45", "mg/dL", ">40", null, null, null))).isEqualTo(new PrintedBand(null, null, ">40"));
        assertThat(drawn(value("Total Cholesterol", "240", "mg/dL", null, "<200", "outside", "high")))
                .as("OUTSIDE high above its high edge").isEqualTo(new PrintedBand(null, 200.0, "<200"));
        assertThat(drawn(value("HDL", "35", "mg/dL", ">40", null, "outside", "low")))
                .as("OUTSIDE low below its low edge").isEqualTo(new PrintedBand(40.0, null, ">40"));
        assertThat(drawn(value("Total Cholesterol", "190", "mg/dL", null, "<200", "outside", "high")))
                .as("OUTSIDE high under its high edge").isEqualTo(new PrintedBand(null, null, "<200"));
        assertThat(drawn(value("HDL", "210", "mg/dL", ">40", null, "outside", "high")))
                .as("OUTSIDE high beside a low edge says nothing the chip says").isEqualTo(new PrintedBand(null, null, ">40"));
    }

    /** Nothing printed is nothing drawn, whatever the chip: never a default. */
    @Test
    void noPrintedRangeDrawsNothingWhateverTheChip() {
        assertThat(drawn(value("Creatinine", "150", "umol/L", null, null, "outside", "high"))).isEqualTo(PrintedBand.NONE);
        assertThat(drawn(value("Creatinine", "90", "umol/L", null, null, null, null))).isEqualTo(PrintedBand.NONE);
    }

    /** The pin and the band read one agreement rule, so the paper page's bar and the trend's band can never disagree. */
    @Test
    void thePinIsDrawnExactlyWhenTheBandAgrees() {
        List<Map<String, Object>> rows = List.of(
                value("Na", "140", "mmol/L", "135", "145", "inside", null),
                value("Creatinine", "132", "umol/L", "59", "104", "outside", "high"),
                value("Creatinine", "90", "umol/L", "59", "104", "critical", "high"),
                value("Creatinine", "90", "umol/L", "59", "104", null, null),
                value("Creatinine", "50", "umol/L", "59", "104", "outside", "high"));
        for (Map<String, Object> row : rows) {
            ValueVerdict.Kind kind = PaperDisplay.kindOf(row);
            String side = PaperDisplay.drawnSide(kind, row);
            assertThat(PaperDisplay.pin(kind, row, side).isPresent()).as(String.valueOf(row))
                    .isEqualTo(PaperDisplay.bandAgrees(kind, row, side));
        }
    }
}
