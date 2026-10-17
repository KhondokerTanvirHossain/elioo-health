package com.elioo.baymax.common.redact;

import com.elioo.baymax.wa.WaFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every log guard compares the log with {@code Redact.id(x)} computed by the same function, so a Redact.id that
 * returned a constant would keep them all green while the log lost every correlation. This asserts the digest is a
 * correlator: stable per id, different between ids, a fixed short shape, and "-" only for nothing.
 */
class RedactTest {

    private static final String IN = WaFixtures.wamid("8801700000000", 1);
    private static final String OUT = WaFixtures.wamid("8801700000000", 2);

    @Test
    void theSameIdAlwaysGivesTheSameDigest() {
        assertThat(Redact.id(IN)).isEqualTo(Redact.id(IN));
    }

    @Test
    void differentIdsGiveDifferentDigestsEvenForTheSameNumber() {
        assertThat(Redact.id(IN)).isNotEqualTo(Redact.id(OUT));
        assertThat(Redact.id(WaFixtures.wamid("8801700000001", 1))).isNotEqualTo(Redact.id(IN));
    }

    @Test
    void aDigestIsTwelveLowercaseHexCharactersAndCarriesNothingOfTheId() {
        assertThat(Redact.id(IN)).matches("[0-9a-f]{12}");
        assertThat(IN).doesNotContain(Redact.id(IN));
    }

    @Test
    void nothingIsADashNotADigest() {
        assertThat(Redact.id(null)).isEqualTo("-");
        assertThat(Redact.id("")).isEqualTo("-");
        assertThat(Redact.id("  ")).isEqualTo("-");
    }
}
