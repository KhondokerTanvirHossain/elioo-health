package com.elioo.baymax.wa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaFixturesTest {

    /** The fixture is only worth having if it is Meta's shape: Meta's documented sample starts exactly like this. */
    @Test
    void theFixtureHasMetasShapeAndCarriesTheNumber() {
        assertThat(WaFixtures.wamid("16503879439")).startsWith("wamid.HBgLMTY1MDM4Nzk0MzkV");
        assertThat(WaFixtures.decodedDigits(WaFixtures.wamid("8801700000000"))).isEqualTo("8801700000000");
        assertThat(WaFixtures.wamid("8801700000000", 1)).isNotEqualTo(WaFixtures.wamid("8801700000000", 2));
    }
}
