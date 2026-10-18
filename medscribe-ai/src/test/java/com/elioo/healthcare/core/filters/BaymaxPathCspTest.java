package com.elioo.healthcare.core.filters;

import com.elioo.baymax.web.adapter.in.BaymaxSecurityHeadersFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;

import static org.assertj.core.api.Assertions.assertThat;

/** D12 / DR-2: Medioo's pages get the Baymax policy and MedScribe keeps its own, whichever filter runs first. */
class BaymaxPathCspTest {

    private static String csp(String path, boolean medscribeFirst) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        WebFilter medscribe = new SecurityHeadersFilter();
        WebFilter baymax = new BaymaxSecurityHeadersFilter();
        WebFilter first = medscribeFirst ? medscribe : baymax;
        WebFilter second = medscribeFirst ? baymax : medscribe;
        first.filter(exchange, ex -> second.filter(ex, e -> e.getResponse().setComplete())).block();
        return exchange.getResponse().getHeaders().getFirst("Content-Security-Policy");
    }

    @ParameterizedTest
    @CsvSource({"/,true", "/,false", "/app,true", "/app,false", "/app/home,true", "/app/home,false",
            "/app/add,true", "/app/add,false",
            "/assets/baymax.css,true", "/assets/baymax.css,false", "/lang/en,true", "/lang/en,false"})
    void baymaxPathsGetSelfHostedFontsAndScriptsAndNothingInlineInEitherOrder(String path, boolean medscribeFirst) {
        // isEqualTo pins the whole policy on the response. The checks after it read that same value: they are the spec
        // of which directives matter (so a CSP constant that gained 'unsafe-inline' still fails), not a second response.
        assertThat(csp(path, medscribeFirst)).isEqualTo(BaymaxSecurityHeadersFilter.CSP)
                .contains("font-src 'self'").contains("script-src 'self'").contains("frame-ancestors 'none'")
                .contains("img-src 'self' https: http: blob:").contains("connect-src 'self'")
                .doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
    }

    /** The other direction: MedScribe's demo and every other path keep exactly what they had. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void medscribeAndOtherPathsKeepTheirPolicy(boolean medscribeFirst) {
        assertThat(csp("/medscribeai/", medscribeFirst)).isEqualTo(SecurityHeadersFilter.MEDSCRIBE_CSP);
        assertThat(csp("/api/v1/baymax/me", medscribeFirst)).isEqualTo(SecurityHeadersFilter.STRICT_CSP);
        assertThat(csp("/appendix", medscribeFirst)).as("a prefix that only looks like /app").isEqualTo(SecurityHeadersFilter.STRICT_CSP);
    }

    /** Through a real WebHttpHandler: the header the browser receives, with the other security headers intact. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theBrowserReceivesEachPolicyThroughTheFilterChain(boolean medscribeFirst) {
        RouterFunction<ServerResponse> pages = RouterFunctions.route()
                .GET("/app/home", r -> ServerResponse.ok().bodyValue("home"))
                .GET("/medscribeai/", r -> ServerResponse.ok().bodyValue("demo")).build();
        WebFilter medscribe = new SecurityHeadersFilter();
        WebFilter baymax = new BaymaxSecurityHeadersFilter();
        WebTestClient client = WebTestClient.bindToRouterFunction(pages)
                .webFilter(medscribeFirst ? medscribe : baymax, medscribeFirst ? baymax : medscribe).build();
        client.get().uri("/app/home").exchange()
                .expectHeader().valueEquals("Content-Security-Policy", BaymaxSecurityHeadersFilter.CSP)
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin");
        client.get().uri("/medscribeai/").exchange()
                .expectHeader().valueEquals("Content-Security-Policy", SecurityHeadersFilter.MEDSCRIBE_CSP);
    }
}
