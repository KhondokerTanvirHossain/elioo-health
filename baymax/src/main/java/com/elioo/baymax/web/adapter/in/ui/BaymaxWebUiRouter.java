package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static com.elioo.baymax.web.adapter.in.ui.WebUiHandler.BASE;

/**
 * {@code /baymax/**}. Two filters instead of the JSON ones: no session sends the browser to the login page
 * rather than a 401 body, and errors become small Bangla pages rather than JSON.
 */
@Slf4j
@Configuration
public class BaymaxWebUiRouter {

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
                .GET(BASE + "/", ui::loginPage)
                .GET(BASE, ui::loginPage)
                .POST(BASE + "/login", ui::login)
                .GET(BASE + "/verify", ui::verifyPage)
                .POST(BASE + "/verify", ui::verify)
                .POST(BASE + "/logout", ui::logout)
                .build();

        RouterFunction<ServerResponse> signedIn = RouterFunctions.route()
                .GET(BASE + "/home", ui::home)
                .GET(BASE + "/patients/{id}", ui::timelinePage)
                .POST(BASE + "/patients/{id}/upload", ui::upload)
                // literal segments before the {id}-only routes, per the router convention
                .GET(BASE + "/documents/{id}/delete", ui::confirmDelete)
                .POST(BASE + "/documents/{id}/delete", ui::delete)
                .GET(BASE + "/documents/{id}/image", ui::image)
                .GET(BASE + "/documents/{id}/page/{n}", ui::pageImage)
                .GET(BASE + "/documents/{id}", ui::documentPage)
                .filter(requireSession)
                .build();

        return open.and(signedIn).filter(BaymaxWebUiRouter::htmlErrors);
    }

    private static Mono<ServerResponse> htmlErrors(org.springframework.web.reactive.function.server.ServerRequest request,
                                                   org.springframework.web.reactive.function.server.HandlerFunction<ServerResponse> next) {
        return Mono.defer(() -> next.handle(request)).onErrorResume(e -> {
            if (e instanceof BaymaxException be) {
                String text = switch (be.status()) {
                    case NOT_FOUND -> "পাওয়া যায়নি।";
                    case FORBIDDEN -> "এই কাজটি করার অনুমতি আপনার নেই।";
                    case UNAUTHORIZED -> null;
                    case PAYMENT_REQUIRED -> "ফ্রি সীমা শেষ। " + be.getMessage();
                    case SERVICE_UNAVAILABLE -> "লগইন এখন চালু নেই।";
                    default -> be.getMessage();
                };
                if (text == null) {
                    return Html.redirect(BASE + "/");
                }
                return Html.status(be.status(), WebUiHandler.TITLE, "<h1>দুঃখিত</h1><div class=\"err\">" + Html.esc(text) + "</div>"
                        + "<p><a class=\"btn quiet\" href=\"" + BASE + "/home\">ফিরে যান</a></p>");
            }
            log.error("[baymax] unhandled error on {} {}: {}", request.method(), request.path(), e.getMessage(), e);
            return Html.status(HttpStatus.INTERNAL_SERVER_ERROR, WebUiHandler.TITLE,
                    "<h1>দুঃখিত</h1><div class=\"err\">কিছু একটা ভুল হয়েছে। একটু পরে আবার চেষ্টা করুন।</div>");
        });
    }
}
