package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.adapter.in.ui.WebUiTestKitAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every route the web UI publishes is a Baymax page for the CSP; the JSON API and MedScribe are not. Derived, not listed. */
class BaymaxCspRegisteredTest {

    @Test
    void everyWebUiRouteIsCoveredAndNothingElseIs() {
        List<String> uris = WebUiTestKitAccess.fixtureUris();
        assertThat(uris).as("fixture URIs of every route").hasSizeGreaterThanOrEqualTo(20);
        assertThat(uris).allSatisfy(u -> assertThat(BaymaxSecurityHeadersFilter.isBaymaxPage(u.replaceAll("\\?.*$", ""))).as(u).isTrue());
        for (String other : List.of("/api/v1/baymax/me", "/medscribeai/", "/actuator/health", "/appendix", "/assetsx")) {
            assertThat(BaymaxSecurityHeadersFilter.isBaymaxPage(other)).as(other).isFalse();
        }
    }
}
