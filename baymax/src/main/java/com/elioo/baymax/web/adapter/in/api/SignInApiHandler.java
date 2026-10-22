package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.LoginCookie;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.adapter.in.WaLink;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.VerifyFailure;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sign-in as JSON for the Next.js screens (Wave 2 plan B4a), the same two steps as {@link
 * com.elioo.baymax.web.adapter.in.ui.SignInPages} over the same use case, cookies and rules:
 * <ul>
 *   <li>{@code POST /app/api/sign-in/request}, {@code {"whatsapp_number", "confirmed"?, "next"?}}: the number shown back
 *       for a yes ({@code confirm.number_display}; nothing requested, no cookie), then, with {@code confirmed: true}, the
 *       code requested. {@code sent} is true only for {@code CodeIssue.SENT}, i.e. WhatsApp accepted the message, and
 *       every other outcome answers the same bytes, so the JSON tells no more about a number than the HTML code page
 *       does: SENT or not-SENT, which does say whether the number is a family's with an open WhatsApp window. The
 *       {@code baymax_login} cookie carries the HMAC, the last three digits and the {@code .s} mark, never the number;</li>
 *   <li>{@code POST /app/api/sign-in/verify}, {@code {"code"}}: a session cookie and {@code {next}}, the held place
 *       through {@link LoginCookie#nextPage} (it feeds client-side navigation, so it is checked when it is used, not
 *       only when it was held), else {@code /app/home}.</li>
 *   <li>(plan W3-api) {@code GET /app/api/sign-in/state}, open: the code step read back from the login cookie;
 *       {@code GET /app/api/sign-in/next}, session: the held place, checked; {@code POST /app/api/sign-out}, session:
 *       the session ended and its cookie cleared.</li>
 * </ul>
 * {@link AppApiGuard#OPEN} holds the two POST routes, {@code sign-in/request} and {@code sign-in/verify}, as open
 * mutations: no session needed, CSRF still applied, {@code Origin} required. It also holds one open read,
 * {@code GET sign-in/state}, which answers from the visitor's own login cookie and writes nothing, so CSRF does not
 * apply to it; {@code sign-in/next} and {@code sign-out} need a session. The open mutations' rule is
 * the CSRF rule for a sessionless mutation, not a defence against automation: a non-browser client sets {@code Origin}
 * and {@code X-Medioo} freely. What bounds abuse is {@code WebAuthService.requestCode}: it never sends for NO_ACCOUNT,
 * NOT_PERMITTED or OUTSIDE_WINDOW, and mints at most {@code otpMaxLivePerHour} codes per number; there is no per-client
 * request-rate limit (the same exposure as the HTML {@code /app/login} form). Refused codes are 400 with a
 * {@code CODE_*} name — never 401, which the client reads as "no session"; any other 401 on verify is {@code CODE_WRONG},
 * as the HTML page shows it, and the guard answers no 401 on an open route whatever a handler does. This handler logs
 * nothing: the guard's access line (method, pattern, status, duration) is the only line, and the number, the code
 * and the hash stay out of it.
 */
@Component
@RequiredArgsConstructor
public class SignInApiHandler {

    private final WebAuthUseCase auth;
    private final SessionAuthFilter sessions;
    private final BaymaxProperties properties;

    /** {@code {"whatsapp_number": "...", "confirmed": true, "next": "/app/..."}}; any other field is ignored. */
    record CodeAsk(@JsonProperty("whatsapp_number") String whatsappNumber, Boolean confirmed, String next) {
    }

    /** {@code {"code": "123456"}}. */
    record CodeEntry(String code) {
    }

    public Mono<ServerResponse> request(ServerRequest request) {
        Lang lang = Lang.of(request);
        return request.bodyToMono(CodeAsk.class).defaultIfEmpty(new CodeAsk(null, null, null)).flatMap(ask -> {
            Optional<String> number = PhoneDisplay.normalise(ask.whatsappNumber() == null ? "" : ask.whatsappNumber());
            if (number.isEmpty()) {
                return ApiReason.INVALID_NUMBER.respond();
            }
            if (!Boolean.TRUE.equals(ask.confirmed())) {
                return json(ServerResponse.ok(), Map.of("confirm", Map.of("number_display", PhoneDisplay.full(number.get()))));
            }
            Optional<String> next = Optional.ofNullable(ask.next()).flatMap(LoginCookie::nextPage);
            return auth.requestCode(number.get()).flatMap(requested -> {
                ServerResponse.BodyBuilder ok = ServerResponse.ok().cookie(LoginCookie.cookie(LoginCookie.NAME,
                        LoginCookie.value(requested.phoneHash(), PhoneDisplay.last3(number.get()), requested.sent()),
                        LoginCookie.TTL, secure()));
                next.ifPresent(n -> ok.cookie(LoginCookie.cookie(LoginCookie.NEXT_NAME, n, LoginCookie.TTL, secure())));
                return json(ok, requested(requested.sent(), PhoneDisplay.last3(number.get()), lang, properties));
            });
        });
    }

    /**
     * {@code GET /app/api/sign-in/state} (plan W3-api; open, no session): the code step read back from the
     * {@code baymax_login} cookie Java wrote, so the Next code page can show the right step after the family comes back
     * from WhatsApp and never parses the cookie itself. {@code {"pending": false}} without a cookie of the shape Java
     * writes — missing, tampered, or the pre-tail bare-hash form, whose 30 minutes ended long ago and which has no tail
     * to show (the page asks for the number again, which writes a fresh one); otherwise {@code pending: true} and
     * exactly what the confirmed request answered ({@link #requested}). Reads the cookie, writes nothing.
     */
    public Mono<ServerResponse> state(ServerRequest request) {
        Optional<LoginCookie.Login> login = LoginCookie.read(request).filter(l -> l.tail() != null);
        if (login.isEmpty()) {
            return json(ServerResponse.ok(), Map.of("pending", false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pending", true);
        body.putAll(requested(login.get().sent(), login.get().tail(), Lang.of(request), properties));
        return json(ServerResponse.ok(), body);
    }

    /**
     * {@code GET /app/api/sign-in/next?next=<encoded>} (plan W3-api; session): the place a signed-in visitor of the
     * sign-in page is sent on to, checked here by {@link LoginCookie#nextPage} exactly as the HTML page checks it, so
     * Next never judges {@code next}. Anything refused, or none, is {@link LoginCookie#HOME}.
     */
    public Mono<ServerResponse> next(ServerRequest request) {
        return json(ServerResponse.ok(), Map.of("next", request.queryParam("next").flatMap(LoginCookie::nextPage).orElse(LoginCookie.HOME)));
    }

    /**
     * {@code POST /app/api/sign-out} (plan W3-api; session, CSRF): the session ended in the store, then 204 with the
     * cleared {@code baymax_session} cookie ({@code Path=/app}, {@code Max-Age=0}), the HTML {@code /app/logout}'s two
     * steps without its redirect.
     */
    public Mono<ServerResponse> signOut(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(Mono.defer(() -> ServerResponse.noContent().cookie(sessions.clearedCookie()).build()));
    }

    public Mono<ServerResponse> verify(ServerRequest request) {
        Optional<LoginCookie.Login> login = LoginCookie.read(request);
        if (login.isEmpty()) {
            return ApiReason.SIGN_IN_RESTART.respond();
        }
        String next = Optional.ofNullable(request.cookies().getFirst(LoginCookie.NEXT_NAME)).map(HttpCookie::getValue)
                .flatMap(LoginCookie::nextPage).orElse(LoginCookie.HOME);
        return request.bodyToMono(CodeEntry.class)
                .map(entry -> entry.code() == null ? "" : entry.code().trim())
                .defaultIfEmpty("")
                .flatMap(code -> auth.verify(login.get().hash(), code))
                .flatMap(issued -> json(ServerResponse.ok()
                        .cookie(sessions.cookie(issued.token()))
                        .cookie(LoginCookie.cookie(LoginCookie.NAME, "", Duration.ZERO, secure()))
                        .cookie(LoginCookie.cookie(LoginCookie.NEXT_NAME, "", Duration.ZERO, secure())),
                        Map.of("next", next)))
                .onErrorResume(CodeRejectedException.class, e -> reason(e.failure()).respond())
                // the HTML page's second rule (SignInPages.verify): any other 401 on this path is the wrong-code copy
                .onErrorResume(BaymaxException.class, e -> e.status() == HttpStatus.UNAUTHORIZED
                        ? ApiReason.CODE_WRONG.respond()
                        : Mono.error(e));
    }

    /**
     * The name a refused code answers with, exactly as fine as the HTML page's copy ({@code SignInPages.refusalKey}):
     * WRONG and NO_CODE are one name, because the page shows them one way.
     */
    static ApiReason reason(VerifyFailure failure) {
        return switch (failure) {
            case WRONG, NO_CODE -> ApiReason.CODE_WRONG;
            case EXPIRED -> ApiReason.CODE_EXPIRED;
            case BURNED -> ApiReason.CODE_BURNED;
            case FORMAT -> ApiReason.CODE_FORMAT;
        };
    }

    /**
     * What a confirmed request answers, and what {@link #state} reads back: whether WhatsApp accepted the code, the
     * tail the code page shows, the numbers its copy needs, and the WhatsApp link when a public number is set. Nothing
     * else varies with the outcome. One method for both answers, so the two cannot drift.
     */
    static Map<String, Object> requested(boolean sent, String tail, Lang lang, BaymaxProperties properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", sent);
        body.put("number_tail", tail);
        body.put("code_ttl_minutes", properties.getAuth().getOtpTtl().toMinutes());
        body.put("max_attempts", properties.getAuth().getOtpMaxAttempts());
        String link = WaLink.of(lang, properties.getWa().getPublicNumber());
        if (link != null) {
            body.put("wa_link", link);
        }
        return body;
    }

    private boolean secure() {
        return properties.getAuth().isCookieSecure();
    }

    private static Mono<ServerResponse> json(ServerResponse.BodyBuilder builder, Object body) {
        return builder.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
