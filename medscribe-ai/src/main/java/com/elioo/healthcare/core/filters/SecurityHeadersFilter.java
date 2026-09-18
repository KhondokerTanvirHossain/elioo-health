package com.elioo.healthcare.core.filters;

import com.elioo.healthcare.core.MedScribePaths;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Security headers on every response (BMX-6b follow-up). Two apps share one origin: the Baymax family app
 * and the MedScribe PoC demo UI. A script injected into the demo page must not reach a family's session, so
 * both get a Content-Security-Policy and neither may be framed.
 *
 * <ul>
 *   <li>Baymax and everything else: no script at all, same-origin stylesheet, images from self or the signed
 *       storage URLs the image endpoints redirect to, forms to self only.</li>
 *   <li>{@code /medscribeai/**}: the PoC page needs its inline script and style and the Tailwind CDN; it gets
 *       exactly those and nothing more (no other script host, connect only to self, no frames).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements WebFilter {

    static final String STRICT_CSP = "default-src 'none'; style-src 'self'; img-src 'self' https: http:; "
            + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    static final String MEDSCRIBE_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
            + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpHeaders h = exchange.getResponse().getHeaders();
        h.set("Content-Security-Policy", path.startsWith(MedScribePaths.PREFIX) ? MEDSCRIBE_CSP : STRICT_CSP);
        h.set("X-Frame-Options", "DENY");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "strict-origin-when-cross-origin");
        return chain.filter(exchange);
    }
}
