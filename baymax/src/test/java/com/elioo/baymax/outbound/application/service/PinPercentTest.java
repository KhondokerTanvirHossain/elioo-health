package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/** The range bar may only tell the story the verdict tells: a pin in the band exactly when the code proved INSIDE. */
class PinPercentTest {

    /** Both directions in one table: the proven-inside rows must land in the band, every other shape must not. */
    @ParameterizedTest(name = "{0} in {1}–{2}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "5.1|4.0|6.0", "4.0|4.0|6.0", "6.0|4.0|6.0", "8.4|4.0|6.0", "3.1|4.0|6.0", "13.0|4.0|6.0",
            "> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5", "Positive|null|null", "250|null|100", "250|null|null", "1,5|1|2"})
    void thePinIsInTheBandExactlyWhenTheValueIsInsideByCode(String value, String low, String high) {
        Map<String, Object> v = UrgencyServiceTest.value("x", value, low, high);
        OptionalDouble pin = UrgencyService.pinPercent(v);
        boolean inBand = pin.isPresent() && pin.getAsDouble() >= 25 && pin.getAsDouble() <= 75;
        assertThat(inBand).isEqualTo(UrgencyService.insideByCode(v));
    }

    @Test
    void anUnparseableReadingHasNoPinAtAll() {
        for (String value : List.of("> 89", "130 / 80", "1,23", "Positive", "Trace")) {
            assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("x", value, "1", "2"))).as(value).isEmpty();
        }
    }

    @Test
    void aFarValueIsClampedToTheBarNotOffIt() {
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "99", "3.5", "5.1")).getAsDouble()).isEqualTo(98.0);
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "0.1", "3.5", "5.1")).getAsDouble()).isEqualTo(2.0);
    }

    /** An inverted or empty range draws no bar: there is no band to place anything in. */
    @Test
    void anInvertedOrZeroWidthRangeHasNoPin() {
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "4", "5.1", "3.5"))).isEmpty();
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "4", "4", "4"))).isEmpty();
    }
}
