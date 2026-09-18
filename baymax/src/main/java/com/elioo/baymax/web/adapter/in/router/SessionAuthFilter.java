package com.elioo.baymax.web.adapter.in.router;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the session cookie into a {@link WebSession} on the request, or answers 401. The cookie is HttpOnly,
 * Secure and SameSite=Lax; the token inside it is random and stored hashed, so the cookie is the only copy.
 */
@Component
@RequiredArgsConstructor
public class SessionAuthFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String SESSION_ATTRIBUTE = "baymax.session";

    private final WebAuthUseCase auth;
    private final BaymaxProperties properties;

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        return resolve(request)
                .flatMap(session -> {
                    request.attributes().put(SESSION_ATTRIBUTE, session);
                    return next.handle(request);
                })
                .switchIfEmpty(Mono.defer(() -> ServerResponse.status(HttpStatus.UNAUTHORIZED)
                        .bodyValue(Map.of("status", 401, "reason", "no_session", "message", "log in first"))));
    }

    /** The live session behind the request's cookie, if any. */
    public Mono<WebSession> resolve(ServerRequest request) {
        return Mono.justOrEmpty(token(request)).flatMap(auth::authenticate);
    }

    public Optional<String> token(ServerRequest request) {
        HttpCookie cookie = request.cookies().getFirst(properties.getAuth().getCookieName());
        return Optional.ofNullable(cookie).map(HttpCookie::getValue).filter(v -> !v.isBlank());
    }

    public static WebSession session(ServerRequest request) {
        return (WebSession) request.attributes().get(SESSION_ATTRIBUTE);
    }

    public ResponseCookie cookie(String token) {
        return build(token, properties.getAuth().getSessionTtl());
    }

    public ResponseCookie clearedCookie() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        BaymaxProperties.Auth cfg = properties.getAuth();
        return ResponseCookie.from(cfg.getCookieName(), value)
                .httpOnly(true).secure(cfg.isCookieSecure()).sameSite("Lax").path("/").maxAge(maxAge).build();
    }
}
