package com.elioo.baymax.web.adapter.in;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * D12: a Content-Security-Policy for Medioo's own pages that allows self-hosted fonts and scripts and nothing inline.
 * MedScribe's filter (medscribe-ai, HIGHEST_PRECEDENCE) sets its header before the chain runs; this one sets the Baymax
 * header at commit time, so it wins whatever order Spring registers the two in, and MedScribe's paths are untouched.
 *
 * <p>The policy ships once, final: {@code font-src 'self'} for the self-hosted Noto fonts (D1), {@code script-src 'self'}
 * for {@code progress.js} (A9) and {@code upload.js} (D4), {@code connect-src 'self'} for their requests, and
 * {@code img-src … blob:} for the upload tray's thumbnails (D4). Nothing inline, nothing eval'd.</p>
 */
@Component
public class BaymaxSecurityHeadersFilter implements WebFilter {

    public static final String CSP = "default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; "
            + "connect-src 'self'; img-src 'self' https: http: blob:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    /** The web UI's paths (BaymaxWebUiRouter): the landing, the app, its assets, the language switch, the old /baymax. */
    public static boolean isBaymaxPage(String path) {
        return path.equals("/") || path.equals("/app") || path.startsWith("/app/") || path.startsWith("/assets/")
                || path.startsWith("/lang/") || path.equals("/baymax") || path.startsWith("/baymax/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isBaymaxPage(exchange.getRequest().getPath().value())) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set("Content-Security-Policy", CSP);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
