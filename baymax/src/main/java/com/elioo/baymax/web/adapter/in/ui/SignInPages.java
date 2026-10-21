package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.LoginCookie;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.adapter.in.WaLink;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.VerifyFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * Number → code → session (spec §4.2, §4.3), and sign-out. The phone number is never in a URL: between the number
 * page and the code page it travels as its HMAC in a short-lived HttpOnly cookie.
 *
 * <p>{@code /app/?next=<path>} keeps the place of a session that ended while a page was open (rebuild plan Review Focus
 * 4: {@code /app/api} answers 401 {@code UNAUTHENTICATED} and the client sends the family here). The path is held in its
 * own short-lived HttpOnly cookie across the number and code steps — the family may leave for WhatsApp in between —
 * and opened after the right code. It is checked with {@link LocalPath} when it is held AND when it is used (the cookie
 * is the client's to write), and {@code /app/api/**} is refused too: a JSON route is not a page to land on. Anything
 * refused is {@code /app/home}, the default.
 */
@Component
@RequiredArgsConstructor
public class SignInPages implements WebPages {

    /** {@link LoginCookie}'s names, kept here for the page tests that set them. */
    static final String LOGIN_COOKIE = LoginCookie.NAME;
    static final String SENT_MARK = LoginCookie.SENT_MARK;
    static final String NEXT_COOKIE = LoginCookie.NEXT_NAME;
    static final String HOME = LoginCookie.HOME;

    private final WebAuthUseCase auth;
    private final SessionAuthFilter sessions;
    private final UiCopy copy;
    private final PageChrome chrome;
    private final com.elioo.baymax.config.BaymaxProperties properties;

    @Override
    public int order() {
        return 11;   // open
    }

    /**
     * The no-JS sign-in fallback (plan W3-api, S1, DR-42): these same pages under {@code /app/basic}, which Caddy keeps
     * on Java after {@code /app/} moves to Next, and which the Next sign-in page links to from {@code <noscript>}.
     */
    public static final String BASIC = BASE + "/basic";

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        // the fallback's literal paths first: no {variable} route under /app may ever take /app/basic
        return routesUnder(BASIC)
                .and(routesUnder(BASE))
                .and(RouterFunctions.route().POST(BASE + "/logout", this::logout).build());
    }

    /**
     * The number page, the number's two posts and the code page with its post, under {@code base}: every form
     * {@code action}, every redirect and the other-number link are built from {@code base}, so a family that started
     * under {@code /app/basic} stays there. Cookies, {@code next=}, the {@link LocalPath} checks and the copy are the
     * same code whatever the base; where sign-in lands ({@code next}, else {@link #HOME}) does not depend on it.
     */
    RouterFunction<ServerResponse> routesUnder(String base) {
        return RouterFunctions.route()
                .GET(base + "/", request -> loginPage(request, base))
                .GET(base, request -> loginPage(request, base))
                .POST(base + "/login", request -> login(request, base))
                .GET(base + "/verify", request -> verifyPage(request, base, null))
                .POST(base + "/verify", request -> verify(request, base))
                .build();
    }

    public Mono<ServerResponse> loginPage(ServerRequest request) {
        return loginPage(request, BASE);
    }

    private Mono<ServerResponse> loginPage(ServerRequest request, String base) {
        Lang lang = Lang.of(request);
        Optional<String> next = request.queryParam("next").flatMap(LoginCookie::nextPage);
        return sessions.resolve(request).flatMap(s -> Html.redirect(next.orElse(HOME)))
                .switchIfEmpty(Mono.defer(() -> {
                    next.ifPresent(n -> request.exchange().getResponse().addCookie(nextCookie(n, LoginCookie.TTL)));
                    return numberPage(lang, base, "", null);
                }));
    }

    private ResponseCookie nextCookie(String value, Duration maxAge) {
        return LoginCookie.cookie(NEXT_COOKIE, value, maxAge, properties.getAuth().isCookieSecure());
    }

    /**
     * {@code sign-in-number} (spec §4.2): one field, no fixed prefix, no placeholder digits, no deep link (D37: that
     * belongs to the code step). The {@code pattern} is a courtesy; the server is the check.
     */
    private Mono<ServerResponse> numberPage(Lang lang, String base, String raw, String error) {
        return chrome.app(lang, chrome.top(lang, null, false)
                + "<h1>" + copy.t(lang, "login.title") + "</h1>"
                + (error == null ? "" : "<div class=\"err\" role=\"alert\">" + error + "</div>")
                + "<form method=\"post\" action=\"" + base + "/login\">"
                + "<label for=\"n\">" + copy.t(lang, "login.number") + "</label>"
                + "<input id=\"n\" type=\"tel\" name=\"whatsapp_number\" autocomplete=\"tel\" inputmode=\"tel\""
                + " pattern=\"[0-9০-৯+ \\-]{8,24}\" value=\"" + esc(raw) + "\" required>"
                + "<p class=\"small\">" + copy.t(lang, "login.hint") + "</p>"
                + "<button type=\"submit\">" + copy.t(lang, "login.next") + "</button>"
                + "</form>");
    }

    /**
     * The number, shown back normalised for a yes ("+880 1700-000000 — ঠিক আছে?"); only the confirmed number reaches
     * {@code requestCode}. DR-23: nothing goes into a URL; the login cookie holds the HMAC, the last three digits and,
     * when WhatsApp accepted an automatic send, the {@code .s} mark the code page reads — never the number.
     */
    public Mono<ServerResponse> login(ServerRequest request) {
        return login(request, BASE);
    }

    private Mono<ServerResponse> login(ServerRequest request, String base) {
        Lang lang = Lang.of(request);
        return request.formData().flatMap(form -> {
            String raw = Optional.ofNullable(form.getFirst("whatsapp_number")).orElse("");
            Optional<String> number = PhoneDisplay.normalise(raw);
            if (number.isEmpty()) {
                return numberPage(lang, base, raw, copy.t(lang, "login.number.invalid"));
            }
            if (!"1".equals(form.getFirst("confirmed"))) {
                return chrome.app(lang, chrome.top(lang, null, false)
                        + "<h1>" + copy.t(lang, "login.title") + "</h1>"
                        + "<p class=\"number-back\">" + esc(PhoneDisplay.full(number.get())) + "</p>"
                        + "<p>" + copy.t(lang, "login.number.confirm") + "</p>"
                        + "<form method=\"post\" action=\"" + base + "/login\">"
                        + "<input type=\"hidden\" name=\"whatsapp_number\" value=\"" + esc(number.get()) + "\">"
                        + "<input type=\"hidden\" name=\"confirmed\" value=\"1\">"
                        + "<button type=\"submit\">" + copy.t(lang, "login.number.yes") + "</button> "
                        + "<a class=\"btn quiet\" href=\"" + base + "/\">" + copy.t(lang, "login.number.change") + "</a>"
                        + "</form>");
            }
            return auth.requestCode(number.get())
                    .flatMap(requested -> ServerResponse.seeOther(URI.create(base + "/verify"))
                            .header(HttpHeaders.SET_COOKIE, loginCookie(LoginCookie.value(requested.phoneHash(),
                                    PhoneDisplay.last3(number.get()), requested.sent()), LoginCookie.TTL).toString())
                            .build());
        });
    }

    public Mono<ServerResponse> verifyPage(ServerRequest request) {
        return verifyPage(request, BASE, null);
    }

    /**
     * {@code sign-in-code} (spec §4.3, amended by the PO 2026-10-18: sign-in is free). Two states and no third:
     * <ul>
     *   <li>WhatsApp accepted the code the web request sent automatically (the cookie's {@code .s}): the page says so,
     *       and the code field is the primary action, with a quiet way to fetch another;</li>
     *   <li>anything else — outside the window, no account, not allowlisted, rate-limited, WhatsApp refused it: the
     *       one-tap button that opens WhatsApp with কোড typed in. The page never says a code was sent.</li>
     * </ul>
     * The two non-sent pages are identical whatever the reason, so the page tells a stranger nothing about a number.
     * No script: the 5-second poll waits for the phase-1b CSP.
     */
    private Mono<ServerResponse> verifyPage(ServerRequest request, String base, String error) {
        Optional<LoginCookie.Login> login = LoginCookie.read(request);
        if (login.isEmpty()) {
            return Html.redirect(base + "/");
        }
        Lang lang = Lang.of(request);
        boolean sent = login.get().sent();
        String link = Optional.ofNullable(WaLink.of(lang, properties.getWa().getPublicNumber())).map(Html::esc).orElse(null);
        StringBuilder b = new StringBuilder(chrome.top(lang, null, false));
        b.append("<h1>").append(copy.t(lang, "login.title")).append("</h1>");
        if (sent) {
            b.append("<p class=\"lede\" role=\"status\">").append(copy.t(lang, "login.code.sent")).append("</p>");
        } else {
            b.append("<p class=\"lede\">").append(copy.t(lang, "login.code.say")).append(" <strong>")
                    .append(copy.t(lang, "login.code.word")).append("</strong></p>");
            if (link != null) {
                b.append("<a class=\"btn wa\" href=\"").append(link).append("\">").append(copy.t(lang, "login.wa.open")).append("</a>")
                        .append("<p class=\"small\">").append(copy.t(lang, "login.wa.hint")).append("</p>");
            }
        }
        Optional.ofNullable(login.get().tail()).ifPresent(tail -> b.append("<p class=\"mask\">")
                .append(copy.t(lang, "login.code.number", "••••••••" + tail)).append("</p>"));
        if (error != null) {
            b.append("<div class=\"err\" role=\"alert\">").append(error).append("</div>");
        }
        b.append("<form method=\"post\" action=\"").append(base).append("/verify\">")
                .append("<label for=\"c\">").append(copy.t(lang, "login.code")).append("</label>")
                .append("<input id=\"c\" class=\"code-in\" type=\"text\" name=\"code\" inputmode=\"numeric\" pattern=\"[0-9০-৯]{6}\" maxlength=\"6\" autocomplete=\"one-time-code\" required>")
                .append("<button type=\"submit\">").append(copy.t(lang, "login.enter")).append("</button>")
                .append("</form>")
                .append("<p class=\"small\">").append(copy.t(lang, "login.code.ttl", properties.getAuth().getOtpTtl().toMinutes())).append("</p>");
        if (sent && link != null) {
            b.append("<p class=\"small\"><a href=\"").append(link).append("\">").append(copy.t(lang, "login.code.resend")).append("</a></p>");
        }
        b.append("<p class=\"small\"><a href=\"").append(base).append("/\">").append(copy.t(lang, "login.other")).append("</a></p>");
        return chrome.app(lang, b.toString());
    }

    public Mono<ServerResponse> verify(ServerRequest request) {
        return verify(request, BASE);
    }

    private Mono<ServerResponse> verify(ServerRequest request, String base) {
        Optional<String> hash = LoginCookie.read(request).map(LoginCookie.Login::hash);
        if (hash.isEmpty()) {
            return Html.redirect(base + "/");
        }
        Lang lang = Lang.of(request);
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("code")).orElse("").trim())
                .flatMap(code -> auth.verify(hash.get(), code))
                .flatMap(issued -> ServerResponse.seeOther(URI.create(Optional.ofNullable(request.cookies().getFirst(NEXT_COOKIE))
                                .map(HttpCookie::getValue).flatMap(LoginCookie::nextPage).orElse(HOME)))
                        .header(HttpHeaders.SET_COOKIE, sessions.cookie(issued.token()).toString())
                        .header(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString())
                        .header(HttpHeaders.SET_COOKIE, nextCookie("", Duration.ZERO).toString())
                        .build())
                .onErrorResume(com.elioo.baymax.web.application.port.in.CodeRejectedException.class,
                        e -> verifyPage(request, base, refusal(lang, e.failure())))
                .onErrorResume(BaymaxException.class, e -> e.status() == HttpStatus.UNAUTHORIZED
                        ? verifyPage(request, base, copy.t(lang, "login.bad.wrong"))
                        : Mono.error(e));
    }

    /** What happened, and the way to a new code (which is on the page whatever the state). */
    private String refusal(Lang lang, VerifyFailure failure) {
        String key = refusalKey(failure);
        return failure == VerifyFailure.BURNED ? copy.t(lang, key, properties.getAuth().getOtpMaxAttempts()) : copy.t(lang, key);
    }

    /**
     * The copy each refused code is shown with. WRONG and NO_CODE share one: the page does not tell a guess at a
     * number that has no live code from a wrong guess. The sign-in JSON names a refusal exactly as finely as this
     * ({@code SignInApiHandlerTest.everyJsonCodeRefusalIsTheHtmlPagesRefusal}), so it reveals nothing the page does not.
     */
    public static String refusalKey(VerifyFailure failure) {
        return switch (failure) {
            case WRONG, NO_CODE -> "login.bad.wrong";
            case EXPIRED -> "login.bad.expired";
            case BURNED -> "login.bad.burned";
            case FORMAT -> "login.bad.format";
        };
    }

    public Mono<ServerResponse> logout(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(ServerResponse.seeOther(URI.create(BASE + "/"))
                        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build());
    }

    private ResponseCookie loginCookie(String value, Duration maxAge) {
        return LoginCookie.cookie(LOGIN_COOKIE, value, maxAge, properties.getAuth().isCookieSecure());
    }
}
