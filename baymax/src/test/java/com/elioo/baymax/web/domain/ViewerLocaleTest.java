package com.elioo.baymax.web.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** F7 for Phase 2: whether the reader is in Bangladesh comes from their own number, +880 and nothing else. */
class ViewerLocaleTest {

    /**
     * Fictional numbers only: +8801700000xxx at home; abroad the UK drama range, the US 555 range and Australia's
     * fictional mobile range; and a number that starts like Bangladesh's code without being it (+881, satellite).
     */
    @ParameterizedTest(name = "{0} is in Bangladesh: {1}")
    @CsvSource({"+8801700000000,true", "+8801700000799,true", "+447700900123,false", "+15555550123,false",
            "+61491570156,false", "+8816000000,false"})
    void theViewersCountryComesFromTheirNumber(String e164, boolean bangladesh) {
        assertThat(ViewerLocale.of(e164).bangladesh()).isEqualTo(bangladesh);
    }
}
