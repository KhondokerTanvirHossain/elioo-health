package com.elioo.healthcare.core.filters;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private MockServerWebExchange run(String path) {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter.filter(ex, e -> Mono.empty()).block();
        return ex;
    }

    @Test
    void baymaxPagesForbidAllScriptAndFraming() {
        for (String path : new String[]{"/", "/app/home", "/app/documents/x", "/assets/baymax.css", "/api/v1/baymax/me"}) {
            var h = run(path).getResponse().getHeaders();
            assertThat(h.getFirst("Content-Security-Policy")).as(path).startsWith("default-src 'none'").contains("frame-ancestors 'none'")
                    .doesNotContain("script-src").doesNotContain("unsafe-inline");
            assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
            assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }

    @Test
    void theMedScribeDemoGetsOnlyWhatItsPageNeeds() {
        var h = run("/medscribeai/").getResponse().getHeaders();
        String csp = h.getFirst("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com").contains("connect-src 'self'")
                .contains("frame-ancestors 'none'").doesNotContain("https: ").doesNotContain("*");
        assertThat(run("/medscribeai/api/v1/medical-report/languages").getResponse().getHeaders().getFirst("Content-Security-Policy")).isEqualTo(csp);
    }
}
