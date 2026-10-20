package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.WaFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F9a: the inbound key is the same HMAC as a number's, domain-separated, so a wamid and a number never share a key. */
class PhoneHasherTest {

    private static PhoneHasher hasher(String secret) {
        BaymaxProperties props = new BaymaxProperties();
        props.getAuth().setHmacSecret(secret);
        return new PhoneHasher(props);
    }

    @Test
    void aProviderIdIsKeyedAsLowercaseHexAndNeverCarriesTheIdOrTheNumber() {
        String wamid = WaFixtures.wamid("8801700000000", 3);
        String key = hasher("s".repeat(32)).providerId(wamid);

        assertThat(key).matches("[0-9a-f]{64}").doesNotContain(wamid).doesNotContain("8801700000000");
        assertThat(key).as("stable: a re-delivery must find the same key").isEqualTo(hasher("s".repeat(32)).providerId(wamid));
        assertThat(key).as("domain-separated from the number hash").isEqualTo(hasher("s".repeat(32)).phone("wamid:" + wamid))
                .isNotEqualTo(hasher("s".repeat(32)).phone(wamid));
        assertThat(key).as("keyed by the server secret, so the table cannot be walked back without it")
                .isNotEqualTo(hasher("t".repeat(32)).providerId(wamid));
    }

    @Test
    void withoutTheSecretThereIsNoKeyAndTheFailureIsTheServiceUnavailableOne() {
        assertThatThrownBy(() -> hasher("").providerId("wamid.x"))
                .isInstanceOf(BaymaxException.class)
                .satisfies(e -> assertThat(((BaymaxException) e).reason()).isEqualTo("auth_not_configured"));
    }
}
