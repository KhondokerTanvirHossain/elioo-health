package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
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

    static final String LOGIN_COOKIE = "baymax_login";
    /** The login cookie's mark for "WhatsApp accepted the code" (DR-39 amended); absent for every other outcome. */
    static final String SENT_MARK = "s";
    /**
     * How long the code page remembers which number it is for. 30 minutes, up from BMX-5's 10, for the button path
     * (PO 2026-10-18): the family leaves the browser, opens WhatsApp, sends কোড, waits for the reply and comes back,
     * and only then does the 10-minute code TTL start. The cookie holds the HMAC and the last three digits, nothing a
     * longer life exposes; the code itself still expires on its own TTL.
     */
    static final Duration LOGIN_COOKIE_TTL = Duration.ofMinutes(30);
    /** The page to open after sign-in ({@code ?next=}), held for the same 30 minutes as the login cookie. */
    static final String NEXT_COOKIE = "baymax_next";
    static final String HOME = BASE + "/home";

    private final WebAuthUseCase auth;
    private final SessionAuthFilter sessions;
    private final UiCopy copy;
    private final PageChrome chrome;
    private final com.elioo.baymax.config.BaymaxProperties properties;

    @Override
    public int order() {
        return 11;   // open
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(BASE + "/", this::loginPage)
                .GET(BASE, this::loginPage)
                .POST(BASE + "/login", this::login)
                .GET(BASE + "/verify", this::verifyPage)
                .POST(BASE + "/verify", this::verify)
                .POST(BASE + "/logout", this::logout)
                .build();
    }

    public Mono<ServerResponse> loginPage(ServerRequest request) {
        Lang lang = Lang.of(request);
        Optional<String> next = request.queryParam("next").flatMap(SignInPages::nextPage);
        return sessions.resolve(request).flatMap(s -> Html.redirect(next.orElse(HOME)))
                .switchIfEmpty(Mono.defer(() -> {
                    next.ifPresent(n -> request.exchange().getResponse().addCookie(nextCookie(n, LOGIN_COOKIE_TTL)));
                    return numberPage(lang, "", null);
                }));
    }

    /**
     * A page under {@code /app} to open after sign-in: {@link LocalPath}'s rule (no other host, no backslash, no dot
     * segment, raw or decoded), minus {@code /} and the JSON namespace {@code /app/api}, raw or decoded. Empty for
     * anything else, which opens {@link #HOME}.
     */
    static Optional<String> nextPage(String raw) {
        String safe = LocalPath.or(raw, null);
        if (safe == null || !safe.startsWith(BASE)) {
            return Optional.empty();
        }
        String decoded = java.net.URLDecoder.decode(safe, java.nio.charset.StandardCharsets.UTF_8);
        for (String p : new String[]{safe, decoded}) {
            String path = p.contains("?") ? p.substring(0, p.indexOf('?')) : p;
            if (path.equals(BASE + "/api") || path.startsWith(BASE + "/api/")) {
                return Optional.empty();
            }
        }
        return Optional.of(safe);
    }

    private ResponseCookie nextCookie(String value, Duration maxAge) {
        return ResponseCookie.from(NEXT_COOKIE, value).httpOnly(true).secure(sessions.cookie("x").isSecure())
                .sameSite("Lax").path(BASE).maxAge(maxAge).build();
    }

    /**
     * {@code sign-in-number} (spec §4.2): one field, no fixed prefix, no placeholder digits, no deep link (D37: that
     * belongs to the code step). The {@code pattern} is a courtesy; the server is the check.
     */
    private Mono<ServerResponse> numberPage(Lang lang, String raw, String error) {
        return chrome.app(lang, chrome.top(lang, null, false)
                + "<h1>" + copy.t(lang, "login.title") + "</h1>"
                + (error == null ? "" : "<div class=\"err\" role=\"alert\">" + error + "</div>")
                + "<form method=\"post\" action=\"" + BASE + "/login\">"
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
        Lang lang = Lang.of(request);
        return request.formData().flatMap(form -> {
            String raw = Optional.ofNullable(form.getFirst("whatsapp_number")).orElse("");
            Optional<String> number = PhoneDisplay.normalise(raw);
            if (number.isEmpty()) {
                return numberPage(lang, raw, copy.t(lang, "login.number.invalid"));
            }
            if (!"1".equals(form.getFirst("confirmed"))) {
                return chrome.app(lang, chrome.top(lang, null, false)
                        + "<h1>" + copy.t(lang, "login.title") + "</h1>"
                        + "<p class=\"number-back\">" + esc(PhoneDisplay.full(number.get())) + "</p>"
                        + "<p>" + copy.t(lang, "login.number.confirm") + "</p>"
                        + "<form method=\"post\" action=\"" + BASE + "/login\">"
                        + "<input type=\"hidden\" name=\"whatsapp_number\" value=\"" + esc(number.get()) + "\">"
                        + "<input type=\"hidden\" name=\"confirmed\" value=\"1\">"
                        + "<button type=\"submit\">" + copy.t(lang, "login.number.yes") + "</button> "
                        + "<a class=\"btn quiet\" href=\"" + BASE + "/\">" + copy.t(lang, "login.number.change") + "</a>"
                        + "</form>");
            }
            return auth.requestCode(number.get())
                    .flatMap(requested -> ServerResponse.seeOther(URI.create(BASE + "/verify"))
                            .header(HttpHeaders.SET_COOKIE, loginCookie(requested.phoneHash() + "." + PhoneDisplay.last3(number.get())
                                    + (requested.sent() ? "." + SENT_MARK : ""), LOGIN_COOKIE_TTL).toString())
                            .build());
        });
    }

    public Mono<ServerResponse> verifyPage(ServerRequest request) {
        return verifyPage(request, null);
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
    private Mono<ServerResponse> verifyPage(ServerRequest request, String error) {
        if (loginHash(request).isEmpty()) {
            return Html.redirect(BASE + "/");
        }
        Lang lang = Lang.of(request);
        boolean sent = loginSent(request);
        String link = waLink(lang);
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
        loginTail(request).ifPresent(tail -> b.append("<p class=\"mask\">")
                .append(copy.t(lang, "login.code.number", "••••••••" + tail)).append("</p>"));
        if (error != null) {
            b.append("<div class=\"err\" role=\"alert\">").append(error).append("</div>");
        }
        b.append("<form method=\"post\" action=\"").append(BASE).append("/verify\">")
                .append("<label for=\"c\">").append(copy.t(lang, "login.code")).append("</label>")
                .append("<input id=\"c\" class=\"code-in\" type=\"text\" name=\"code\" inputmode=\"numeric\" pattern=\"[0-9০-৯]{6}\" maxlength=\"6\" autocomplete=\"one-time-code\" required>")
                .append("<button type=\"submit\">").append(copy.t(lang, "login.enter")).append("</button>")
                .append("</form>")
                .append("<p class=\"small\">").append(copy.t(lang, "login.code.ttl", properties.getAuth().getOtpTtl().toMinutes())).append("</p>");
        if (sent && link != null) {
            b.append("<p class=\"small\"><a href=\"").append(link).append("\">").append(copy.t(lang, "login.code.resend")).append("</a></p>");
        }
        b.append("<p class=\"small\"><a href=\"").append(BASE).append("/\">").append(copy.t(lang, "login.other")).append("</a></p>");
        return chrome.app(lang, b.toString());
    }

    /** D37: the wa.me deep link to Medioo's own number with the word typed in; null when no public number is set. */
    private String waLink(Lang lang) {
        String number = properties.getWa().getPublicNumber() == null ? "" : properties.getWa().getPublicNumber().trim();
        if (number.isEmpty()) {
            return null;
        }
        String word = lang == Lang.BN ? "কোড" : "code";
        return "https://wa.me/" + esc(number) + "?text=" + java.net.URLEncoder.encode(word, java.nio.charset.StandardCharsets.UTF_8);
    }

    public Mono<ServerResponse> verify(ServerRequest request) {
        Optional<String> hash = loginHash(request);
        if (hash.isEmpty()) {
            return Html.redirect(BASE + "/");
        }
        Lang lang = Lang.of(request);
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("code")).orElse("").trim())
                .flatMap(code -> auth.verify(hash.get(), code))
                .flatMap(issued -> ServerResponse.seeOther(URI.create(Optional.ofNullable(request.cookies().getFirst(NEXT_COOKIE))
                                .map(HttpCookie::getValue).flatMap(SignInPages::nextPage).orElse(HOME)))
                        .header(HttpHeaders.SET_COOKIE, sessions.cookie(issued.token()).toString())
                        .header(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString())
                        .header(HttpHeaders.SET_COOKIE, nextCookie("", Duration.ZERO).toString())
                        .build())
                .onErrorResume(com.elioo.baymax.web.application.port.in.CodeRejectedException.class,
                        e -> verifyPage(request, refusal(lang, e.failure())))
                .onErrorResume(BaymaxException.class, e -> e.status() == HttpStatus.UNAUTHORIZED
                        ? verifyPage(request, copy.t(lang, "login.bad.wrong"))
                        : Mono.error(e));
    }

    /** What happened, and the way to a new code (which is on the page whatever the state). */
    private String refusal(Lang lang, com.elioo.baymax.web.domain.VerifyFailure failure) {
        return switch (failure) {
            case WRONG, NO_CODE -> copy.t(lang, "login.bad.wrong");
            case EXPIRED -> copy.t(lang, "login.bad.expired");
            case BURNED -> copy.t(lang, "login.bad.burned", properties.getAuth().getOtpMaxAttempts());
            case FORMAT -> copy.t(lang, "login.bad.format");
        };
    }

    public Mono<ServerResponse> logout(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(ServerResponse.seeOther(URI.create(BASE + "/"))
                        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build());
    }

    /**
     * {@code <hmac>[.<last three digits>][.s]}. The bare-hash form is still accepted: a cookie from before this release
     * may be in flight for its ten minutes after the deploy.
     */
    private static final java.util.regex.Pattern LOGIN_VALUE =
            java.util.regex.Pattern.compile("^([0-9a-f]{64})(?:\\.([0-9]{3}))?(?:\\.(" + SENT_MARK + "))?$");

    private Optional<java.util.regex.Matcher> loginValue(ServerRequest request) {
        HttpCookie c = request.cookies().getFirst(LOGIN_COOKIE);
        return Optional.ofNullable(c).map(HttpCookie::getValue).map(LOGIN_VALUE::matcher).filter(java.util.regex.Matcher::matches);
    }

    private Optional<String> loginHash(ServerRequest request) {
        return loginValue(request).map(m -> m.group(1));
    }

    private Optional<String> loginTail(ServerRequest request) {
        return loginValue(request).map(m -> m.group(2));
    }

    /** True only when the code request behind this cookie came back SENT: WhatsApp accepted the message. */
    private boolean loginSent(ServerRequest request) {
        return loginValue(request).map(m -> m.group(3) != null).orElse(false);
    }

    private ResponseCookie loginCookie(String value, Duration maxAge) {
        return ResponseCookie.from(LOGIN_COOKIE, value).httpOnly(true).secure(sessions.cookie("x").isSecure())
                .sameSite("Lax").path(BASE).maxAge(maxAge).build();
    }
}
