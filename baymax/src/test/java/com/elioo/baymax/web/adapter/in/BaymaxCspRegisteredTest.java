package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.adapter.in.ui.WebUiTestKitAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    /**
     * The predicate above says which paths are Baymax pages; this says the filter actually puts the policy on what the
     * browser receives from each of them, whatever the method (a POST page, the /baymax redirects, a stylesheet).
     */
    @Test
    void theFilterPutsThePolicyOnTheResponseOfEveryRoute() {
        Map<String, String> csp = WebUiTestKitAccess.headerOnEveryRoute(new BaymaxSecurityHeadersFilter(), "Content-Security-Policy");
        assertThat(csp).as("every route, with every fixture, called").hasSizeGreaterThanOrEqualTo(20);
        assertThat(csp.keySet()).as("POST routes are called too").anyMatch(k -> k.startsWith("POST "));
        assertThat(csp.keySet()).as("the old /baymax redirects are called too").anyMatch(k -> k.contains(" /baymax"));
        assertThat(csp).allSatisfy((route, value) -> assertThat(value).as(route).isEqualTo(BaymaxSecurityHeadersFilter.CSP));
    }
}
