package com.elioo.baymax.web.adapter.in.api;

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
 *       every other outcome answers the same bytes, so the JSON tells a stranger nothing about a number. The
 *       {@code baymax_login} cookie carries the HMAC, the last three digits and the {@code .s} mark, never the number;</li>
 *   <li>{@code POST /app/api/sign-in/verify}, {@code {"code"}}: a session cookie and {@code {next}}, the held place
 *       through {@link LoginCookie#nextPage} (it feeds client-side navigation, so it is checked when it is used, not
 *       only when it was held), else {@code /app/home}.</li>
 * </ul>
 * Both routes are in {@link AppApiGuard#OPEN}: no session needed, CSRF still applied, {@code Origin} required. Refused
 * codes are 400 with a {@code CODE_*} name — never 401, which the client reads as "no session". This handler logs
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
                return json(ok, requested(requested, number.get(), lang));
            });
        });
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
                .onErrorResume(CodeRejectedException.class, e -> reason(e.failure()).respond());
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
     * What a confirmed request answers: whether WhatsApp accepted the code, the tail the code page shows, the numbers
     * its copy needs, and the WhatsApp link when a public number is set. Nothing else varies with the outcome.
     */
    private Map<String, Object> requested(CodeRequest requested, String number, Lang lang) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", requested.sent());
        body.put("number_tail", PhoneDisplay.last3(number));
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
