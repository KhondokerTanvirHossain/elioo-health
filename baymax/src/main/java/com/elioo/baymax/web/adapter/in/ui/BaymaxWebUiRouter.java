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
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static com.elioo.baymax.web.adapter.in.ui.WebUiHandler.BASE;

/**
 * Baymax serves {@code /} (DR-11): the landing page at {@code /}, the app under {@code /app/**}, the
 * stylesheet and landing images under {@code /assets/**}, the language switch at {@code /lang/{code}}, and a
 * permanent redirect from every old {@code /baymax/**} URL to its {@code /app/**} twin so a saved timeline
 * link still opens. Two filters instead of the JSON ones: no session sends the browser to the login page
 * rather than a 401 body, and errors become small pages in the request's language rather than JSON.
 */
@Slf4j
@Configuration
public class BaymaxWebUiRouter {

    static final String OLD_BASE = "/baymax";

    @Bean
    public RouterFunction<ServerResponse> baymaxWebUiRoutes(WebUiHandler ui, SessionAuthFilter sessions) {
        HandlerFilterFunction<ServerResponse, ServerResponse> requireSession = (request, next) ->
                sessions.resolve(request)
                        .flatMap(session -> {
                            request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE, session);
                            return next.handle(request);
                        })
                        .switchIfEmpty(Mono.defer(() -> Html.redirect(BASE + "/")));

        RouterFunction<ServerResponse> open = RouterFunctions.route()
                .GET("/", ui::landing)
                .GET("/lang/{code}", ui::switchLang)
                .GET("/assets/{file}", ui::asset)
                .GET(OLD_BASE, BaymaxWebUiRouter::movedToApp)
                .GET(OLD_BASE + "/{*rest}", BaymaxWebUiRouter::movedToApp)
                .GET(BASE + "/", ui::loginPage)
                .GET(BASE, ui::loginPage)
                .POST(BASE + "/login", ui::login)
                .GET(BASE + "/verify", ui::verifyPage)
                .POST(BASE + "/verify", ui::verify)
                .POST(BASE + "/logout", ui::logout)
                // BMX-8: opt-out from a message link, no session — the link carries an HMAC token
                .GET(BASE + "/nudges/opt-out", ui::optOutPage)
                .POST(BASE + "/nudges/opt-out", ui::optOutApply)
                .build();

        RouterFunction<ServerResponse> signedIn = RouterFunctions.route()
                .GET(BASE + "/home", ui::home)
                .GET(BASE + "/patients/{id}", ui::timelinePage)
                .POST(BASE + "/patients/{id}/upload", ui::upload)
                .GET(BASE + "/patients/{id}/profile", ui::profilePage)
                .POST(BASE + "/patients/{id}/profile", ui::saveProfile)
                .POST(BASE + "/patients/{id}/nudges/opt-out", ui::optOutFromProfile)
                // literal segments before the {id}-only routes, per the router convention
                .GET(BASE + "/documents/{id}/delete", ui::confirmDelete)
                .POST(BASE + "/documents/{id}/delete", ui::delete)
                .GET(BASE + "/documents/{id}/image", ui::image)
                .GET(BASE + "/documents/{id}/page/{n}", ui::pageImage)
                .GET(BASE + "/documents/{id}", ui::documentPage)
                .filter(requireSession)
                .build();

        return open.and(signedIn).filter((request, next) -> htmlErrors(ui, request, next));
    }

    /** {@code /baymax[/rest][?query]} → 301 {@code /app[/rest][?query]}. */
    static Mono<ServerResponse> movedToApp(ServerRequest request) {
        String path = request.path();
        String rest = path.length() > OLD_BASE.length() ? path.substring(OLD_BASE.length()) : "";
        String query = request.uri().getRawQuery();
        String target = BASE + rest + (query == null ? "" : "?" + query);
        return ServerResponse.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, target).build();
    }

    private static Mono<ServerResponse> htmlErrors(WebUiHandler ui, ServerRequest request, HandlerFunction<ServerResponse> next) {
        return Mono.defer(() -> next.handle(request)).onErrorResume(e -> {
            if (e instanceof BaymaxException be) {
                String text = switch (be.status()) {
                    case NOT_FOUND -> ui.text(request, "app.err.notfound");
                    case FORBIDDEN -> ui.text(request, "app.err.forbidden");
                    case UNAUTHORIZED -> null;
                    case PAYMENT_REQUIRED -> ui.text(request, "app.err.freetier", be.getMessage());
                    case SERVICE_UNAVAILABLE -> ui.text(request, "app.err.loginoff");
                    default -> Html.esc(be.getMessage());
                };
                if (text == null) {
                    return Html.redirect(BASE + "/");
                }
                return ui.errorPage(request, be.status(), text);
            }
            log.error("[baymax] unhandled error on {} {}: {}", request.method(), request.path(), e.getMessage(), e);
            return ui.errorPage(request, HttpStatus.INTERNAL_SERVER_ERROR, ui.text(request, "app.err.generic"));
        });
    }
}
