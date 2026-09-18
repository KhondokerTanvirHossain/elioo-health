package com.elioo.baymax.web.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/** {@code /api/v1/baymax/auth/*}: request a code, verify it, log out. */
@Component
@RequiredArgsConstructor
public class AuthHandler {

    private final WebAuthUseCase auth;
    private final SessionAuthFilter sessions;

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> request(ServerRequest request) {
        return request.bodyToMono(Map.class)
                .map(body -> String.valueOf(((Map<String, Object>) body).getOrDefault("whatsapp_number", "")))
                .defaultIfEmpty("")
                .flatMap(auth::requestCode)
                // 200 whatever happened; the body promises nothing about the number
                .then(ServerResponse.ok().bodyValue(Map.of("status", "ok",
                        "message", "if this number has an account, a code is on its way")));
    }

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> verify(ServerRequest request) {
        return request.bodyToMono(Map.class)
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "a JSON body is required")))
                .flatMap(body -> auth.verifyNumber(String.valueOf(((Map<String, Object>) body).getOrDefault("whatsapp_number", "")),
                        String.valueOf(((Map<String, Object>) body).getOrDefault("code", ""))))
                .flatMap(issued -> ServerResponse.ok()
                        .header(HttpHeaders.SET_COOKIE, sessions.cookie(issued.token()).toString())
                        .bodyValue(Map.of("status", "ok", "family_id", issued.session().familyId().toString(),
                                "expires_at", issued.session().expiresAt().toString())));
    }

    public Mono<ServerResponse> logout(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(ServerResponse.noContent()
                        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build());
    }
}
