package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * {@code PUT /app/api/preferences} (plan W10-api, S12): the theme as the cookie {@code baymax_theme} and nothing else.
 * Nothing is stored server-side; Next's root layout reads the cookie and sets {@code data-theme} on {@code <html>} for
 * {@code light} or {@code dark}, and leaves it off for {@code auto} (the phone's own scheme).
 *
 * <ul>
 *   <li>The body is exactly {@code {"theme": "auto" | "light" | "dark"}}: one field, one of three strings, as written.
 *       Any other value, an extra or missing field, a non-object body or no body is 400 {@code INVALID} and sets no
 *       cookie, so a client never holds a theme the layout cannot read.</li>
 *   <li>The cookie is {@code Path=/app} (never {@code /}: MedScribe's pages are not Medioo's), a year, {@code SameSite=Lax},
 *       {@code HttpOnly} (only the server reads it), and {@code Secure} exactly when the session cookie is
 *       ({@code baymax.auth.cookie-secure}).</li>
 *   <li>Session and CSRF are {@link AppApiGuard}'s, as for every mutation.</li>
 * </ul>
 * Nothing here logs: the guard's access line is the only one.
 */
@Component
@RequiredArgsConstructor
public class PreferencesApiHandler {

    static final String COOKIE = "baymax_theme";
    static final String PATH = "/app";
    static final Duration MAX_AGE = Duration.ofDays(365);
    static final Set<String> THEMES = Set.of("auto", "light", "dark");

    private final BaymaxProperties properties;

    public Mono<ServerResponse> preferences(ServerRequest request) {
        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> Mono.justOrEmpty(theme(body)))
                .switchIfEmpty(Mono.error(() -> BaymaxException.badRequest("invalid_request", "theme must be auto, light or dark")))
                .flatMap(theme -> ServerResponse.noContent().cookie(cookie(theme)).build());
    }

    /** The theme of a body that is exactly {@code {"theme": <one of THEMES>}}; empty for anything else. */
    static Optional<String> theme(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1) {
            return Optional.empty();
        }
        JsonNode theme = body.get("theme");
        return theme != null && theme.isTextual() && THEMES.contains(theme.textValue())
                ? Optional.of(theme.textValue()) : Optional.empty();
    }

    private ResponseCookie cookie(String theme) {
        return ResponseCookie.from(COOKIE, theme).path(PATH).maxAge(MAX_AGE).sameSite("Lax").httpOnly(true)
                .secure(properties.getAuth().isCookieSecure()).build();
    }
}
