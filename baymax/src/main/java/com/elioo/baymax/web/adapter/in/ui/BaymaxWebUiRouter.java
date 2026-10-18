package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * Baymax serves {@code /} (DR-11): the landing page at {@code /}, the app under {@code /app/**}, the
 * stylesheet and landing images under {@code /assets/**}, the language switch at {@code /lang/{code}}, and a
 * permanent redirect from every old {@code /baymax/**} URL to its {@code /app/**} twin so a saved timeline
 * link still opens. Two filters instead of the JSON ones: no session sends the browser to the login page
 * rather than a 401 body, and errors become small pages in the request's language rather than JSON.
 *
 * <p>The router routes nothing itself: each screen's {@link WebPages} publishes its own routes, composed here in
 * {@link WebPages#order()}, and applies {@code requireSession} to its signed-in routes.
 */
@Slf4j
@Configuration
public class BaymaxWebUiRouter {

    static final String OLD_BASE = "/baymax";

    @Bean
    public RouterFunction<ServerResponse> baymaxWebUiRoutes(List<WebPages> pages, SessionAuthFilter sessions, PageChrome chrome) {
        HandlerFilterFunction<ServerResponse, ServerResponse> requireSession = (request, next) ->
                sessions.resolve(request)
                        .flatMap(session -> {
                            request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE, session);
                            return next.handle(request);
                        })
                        .switchIfEmpty(Mono.defer(() -> Html.redirect(PageChrome.BASE + "/")));
        RouterFunction<ServerResponse> all = pages.stream().sorted(Comparator.comparingInt(WebPages::order))
                .map(p -> p.routes(requireSession)).reduce(RouterFunction::and)
                .orElseThrow(() -> new IllegalStateException("no web pages registered"));
        return all.filter((request, next) -> htmlErrors(chrome, request, next));
    }

    /** {@code /baymax[/rest][?query]} → 301 {@code /app[/rest][?query]}. */
    static Mono<ServerResponse> movedToApp(ServerRequest request) {
        String path = request.path();
        String rest = path.length() > OLD_BASE.length() ? path.substring(OLD_BASE.length()) : "";
        String query = request.uri().getRawQuery();
        String target = BASE + rest + (query == null ? "" : "?" + query);
        return ServerResponse.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, target).build();
    }

    private static Mono<ServerResponse> htmlErrors(PageChrome chrome, ServerRequest request, HandlerFunction<ServerResponse> next) {
        return Mono.defer(() -> next.handle(request)).onErrorResume(e -> {
            if (e instanceof BaymaxException be) {
                String text = switch (be.status()) {
                    case NOT_FOUND -> chrome.text(request, "app.err.notfound");
                    case FORBIDDEN -> chrome.text(request, "app.err.forbidden");
                    case UNAUTHORIZED -> null;
                    case PAYMENT_REQUIRED -> chrome.text(request, "app.err.freetier", be.getMessage());
                    case SERVICE_UNAVAILABLE -> chrome.text(request, "app.err.loginoff");
                    default -> Html.esc(be.getMessage());
                };
                if (text == null) {
                    return Html.redirect(BASE + "/");
                }
                return chrome.errorPage(request, be.status(), text);
            }
            log.error("[baymax] unhandled error on {} {}: {}", request.method(), request.path(), e.getMessage(), e);
            return chrome.errorPage(request, HttpStatus.INTERNAL_SERVER_ERROR, chrome.text(request, "app.err.generic"));
        });
    }
}
