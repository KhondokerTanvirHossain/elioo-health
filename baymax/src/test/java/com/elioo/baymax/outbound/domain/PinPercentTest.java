package com.elioo.baymax.outbound.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The range bar may only tell the story the verdict tells: a pin in the band exactly when the code proved INSIDE. The pin
 * lives on {@link PrintedRange} beside {@link PrintedRange#insideByCode}, so a page reads both from one parser and never
 * calls the rule that sets levels (HexagonalArchitectureTest).
 */
class PinPercentTest {

    private static Map<String, Object> value(String name, String v, String low, String high) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("value", v);
        if (low != null) {
            m.put("ref_low", low);
            m.put("ref_high", high);
        }
        return m;
    }

    /** Both directions in one table: the proven-inside rows must land in the band, every other shape must not. */
    @ParameterizedTest(name = "{0} in {1}–{2}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "5.1|4.0|6.0", "4.0|4.0|6.0", "6.0|4.0|6.0", "8.4|4.0|6.0", "3.1|4.0|6.0", "13.0|4.0|6.0",
            "> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5", "Positive|null|null", "250|null|100", "250|null|null", "1,5|1|2"})
    void thePinIsInTheBandExactlyWhenTheValueIsInsideByCode(String value, String low, String high) {
        Map<String, Object> v = value("x", value, low, high);
        OptionalDouble pin = PrintedRange.pinPercent(v);
        boolean inBand = pin.isPresent() && pin.getAsDouble() >= 25 && pin.getAsDouble() <= 75;
        assertThat(inBand).isEqualTo(PrintedRange.insideByCode(v));
    }

    @Test
    void anUnparseableReadingHasNoPinAtAll() {
        for (String value : List.of("> 89", "130 / 80", "1,23", "Positive", "Trace")) {
            assertThat(PrintedRange.pinPercent(value("x", value, "1", "2"))).as(value).isEmpty();
        }
    }

    @Test
    void aFarValueIsClampedToTheBarNotOffIt() {
        assertThat(PrintedRange.pinPercent(value("k", "99", "3.5", "5.1")).getAsDouble()).isEqualTo(98.0);
        assertThat(PrintedRange.pinPercent(value("k", "0.1", "3.5", "5.1")).getAsDouble()).isEqualTo(2.0);
    }

    /** An inverted or empty range draws no bar: there is no band to place anything in. */
    @Test
    void anInvertedOrZeroWidthRangeHasNoPin() {
        assertThat(PrintedRange.pinPercent(value("k", "4", "5.1", "3.5"))).isEmpty();
        assertThat(PrintedRange.pinPercent(value("k", "4", "4", "4"))).isEmpty();
    }
}
