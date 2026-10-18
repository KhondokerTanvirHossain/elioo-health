package com.elioo.baymax.web.adapter.in.ui;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/** BMX-8: opt-out from a message link, no session — the link carries an HMAC token. */
@Component
@RequiredArgsConstructor
public class OptOutPages implements WebPages {

    private final com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase optOut;
    private final UiCopy copy;
    private final PageChrome chrome;

    @Override
    public int order() {
        return 12;   // open
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                // BMX-8: opt-out from a message link, no session — the link carries an HMAC token
                .GET(BASE + "/nudges/opt-out", this::optOutPage)
                .POST(BASE + "/nudges/opt-out", this::optOutApply)
                .build();
    }

    public Mono<ServerResponse> optOutPage(ServerRequest request) {
        Lang lang = Lang.of(request);
        UUID patientId;
        try {
            patientId = UUID.fromString(request.queryParam("p").orElse(""));
        } catch (IllegalArgumentException e) {
            return badLink(lang);
        }
        String token = request.queryParam("t").orElse("");
        if (!optOut.tokenValid(patientId, token)) {
            return badLink(lang);
        }
        return chrome.app(lang, chrome.top(lang, null, false) + "<h1>" + copy.t(lang, "optout.title") + "</h1><div class=\"card\"><p>" + copy.t(lang, "optout.q") + "</p>"
                + "<form method=\"post\" action=\"" + BASE + "/nudges/opt-out\"><input type=\"hidden\" name=\"p\" value=\"" + patientId + "\">"
                + "<input type=\"hidden\" name=\"t\" value=\"" + esc(token) + "\">"
                + "<button type=\"submit\" name=\"scope\" value=\"patient\">" + copy.t(lang, "optout.patient") + "</button> "
                + "<button type=\"submit\" name=\"scope\" value=\"family\" class=\"quiet\">" + copy.t(lang, "optout.family") + "</button></form>"
                + "<p class=\"small\"><a href=\"/\">" + copy.t(lang, "optout.keep") + "</a></p></div>");
    }

    public Mono<ServerResponse> optOutApply(ServerRequest request) {
        Lang lang = Lang.of(request);
        return request.formData().flatMap(form -> {
            UUID patientId;
            try {
                patientId = UUID.fromString(Optional.ofNullable(form.getFirst("p")).orElse(""));
            } catch (IllegalArgumentException e) {
                return badLink(lang);
            }
            if (!optOut.tokenValid(patientId, form.getFirst("t"))) {
                return badLink(lang);
            }
            boolean family = "family".equals(form.getFirst("scope"));
            Mono<Void> done = family ? optOut.optOutFamily(patientId) : optOut.optOutPatient(patientId);
            // confirmed back, the moment it is stored
            return done.then(chrome.app(lang, chrome.top(lang, null, false) + "<h1>" + copy.t(lang, "optout.title") + "</h1><div class=\"card\">"
                    + copy.t(lang, family ? "optout.done.family" : "optout.done.patient") + "</div>"));
        });
    }

    private Mono<ServerResponse> badLink(Lang lang) {
        return Html.html(HttpStatus.NOT_FOUND, Html.page(lang, copy.t(lang, "app.home.title"), "<h1>" + copy.t(lang, "app.sorry") + "</h1><div class=\"err\">"
                + copy.t(lang, "optout.badlink") + "</div>", 0, false, copy.t(lang, "disclaimer"), false));
    }
}
