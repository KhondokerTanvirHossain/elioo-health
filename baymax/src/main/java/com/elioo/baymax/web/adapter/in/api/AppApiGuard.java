package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

/**
 * Everything every {@code /app/api} route shares, in this order:
 * <ol>
 *   <li>the session, resolved by {@link SessionAuthFilter#resolve} (the one resolver: the same {@code Path=/app} cookie,
 *       the same store, the same sliding expiry as the HTML pages). None is a 401 {@code UNAUTHENTICATED} — never the
 *       HTML pages' 303, so a page left open past its session learns why and can send the family to sign in. The
 *       routes in {@link #OPEN} (sign-in) need none: a session that resolves is still put on the request, and they never
 *       answer 401 at all;</li>
 *   <li>for a mutation (POST, PUT, PATCH, DELETE), CSRF: the header {@code X-Medioo: 1} (403 {@code MISSING_HEADER}); an
 *       {@code Origin} equal to {@code baymax.web.public-origin} (403 {@code FORBIDDEN_ORIGIN}); and a JSON or
 *       multipart body (415 {@code UNSUPPORTED_MEDIA_TYPE}). A cross-site form can set neither the header nor the JSON
 *       type without a CORS preflight, and there is no CORS configuration to answer one. On an {@link #OPEN} route the
 *       {@code Origin} must be present; on a session route it is checked whenever one is sent (PO decision P2, Wave 2
 *       plan: the master constraint says present there too, and the shipped admission stands until it is ruled);</li>
 *   <li>errors as {@code {"reason":"<NAME>"}} ({@link ApiReason#of}), never a developer message; a status with no name
 *       yet is bodiless, and every 5xx leaves one ERROR line with the exception's class (never its message);</li>
 *   <li>{@code Cache-Control: no-store} on every answer (family data, and a 401 that must not be replayed from a cache);</li>
 *   <li>one INFO line per request: method, the matched route PATTERN ({@code /app/api/**} for the catch-all), status,
 *       duration. Never the path the client sent: on the catch-all that is whatever was typed, before any session
 *       check, and a name or number in it would be logged percent-encoded. Never a body, a query, a name, a number.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppApiGuard implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String HEADER = "X-Medioo";
    private static final Set<HttpMethod> MUTATING = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    /**
     * The route patterns that need no session: the two sign-in steps. They are the first mutations that need none, so
     * on these a request with no {@code Origin} at all is 403 {@code FORBIDDEN_ORIGIN} (browsers send {@code Origin} on
     * every fetch POST). A present, matching {@code Origin} is the CSRF rule for a sessionless mutation: it blocks
     * cross-site browsers and naive callers, NOT a scripted caller, which can forge it along with {@code X-Medioo}. Never
     * lean on it against automation. What bounds abuse of {@code sign-in/request} is {@code WebAuthService.requestCode}:
     * it never sends for NO_ACCOUNT, NOT_PERMITTED or OUTSIDE_WINDOW, and mints at most {@code otpMaxLivePerHour} codes
     * per number. An open route never answers 401 (see {@link #filter}).
     */
    static final Set<String> OPEN = Set.of(AppApiRouter.BASE + "/sign-in/request", AppApiRouter.BASE + "/sign-in/verify");

    private final SessionAuthFilter sessions;
    private final BaymaxProperties properties;

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        long started = System.nanoTime();
        request.exchange().getResponse().getHeaders().setCacheControl("no-store");
        return sessions.resolve(request).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(session -> session.isEmpty() && !open(request)
                        ? ApiReason.UNAUTHENTICATED.respond()
                        : admitted(request, next, session))
                .onErrorResume(e -> error(request, e))
                .flatMap(response -> neverUnauthenticatedWhenOpen(request, response))
                .doOnNext(response -> log.info("[baymax-api] {} {} {} {}ms", request.method(), route(request),
                        response.statusCode().value(), (System.nanoTime() - started) / 1_000_000));
    }

    /**
     * The pattern the router matched ({@code /app/api/me}, {@code /app/api/**}), which Spring records on the request
     * before this filter runs. Ids, when a route has them, stay in the pattern's variables and out of the log line.
     */
    static String route(ServerRequest request) {
        Object pattern = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof PathPattern p ? p.getPatternString() : AppApiRouter.BASE + "/**";
    }

    /**
     * The one place "an open route never answers 401" is asserted, after every answer is made (a handler's own 401
     * response, or a 401 error mapped by {@link #error}): 401 is "no session, go and sign in", and these are the sign-in
     * routes, so a 401 there would send the family from the sign-in page to the sign-in page. It is a handler defect,
     * answered {@code SIGN_IN_RESTART} and left as one WARN line with the route pattern.
     */
    private static Mono<ServerResponse> neverUnauthenticatedWhenOpen(ServerRequest request, ServerResponse response) {
        if (response.statusCode().value() != 401 || !open(request)) {
            return Mono.just(response);
        }
        log.warn("[baymax-api] 401 on open route {} {} answered SIGN_IN_RESTART", request.method(), route(request));
        return ApiReason.SIGN_IN_RESTART.respond();
    }

    /** A route matched by one of the {@link #OPEN} patterns (never the catch-all, whose pattern is {@code /app/api/**}). */
    private static boolean open(ServerRequest request) {
        return OPEN.contains(route(request));
    }

    private Mono<ServerResponse> admitted(ServerRequest request, HandlerFunction<ServerResponse> next, Optional<WebSession> session) {
        session.ifPresent(s -> request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE, s));
        Optional<ApiReason> refused = MUTATING.contains(request.method()) ? csrf(request, open(request)) : Optional.empty();
        if (refused.isPresent()) {
            return refused.get().respond();
        }
        // defer: a handler that throws before returning its Mono is mapped like one that signals an error
        return Mono.defer(() -> next.handle(request));
    }

    /**
     * The three rules, in the order the tests name them; the first broken one is the answer. {@code originRequired}: a
     * missing {@code Origin} is refused too (the {@link #OPEN} routes); otherwise only a foreign one is.
     */
    private Optional<ApiReason> csrf(ServerRequest request, boolean originRequired) {
        if (!"1".equals(request.headers().firstHeader(HEADER))) {
            return Optional.of(ApiReason.MISSING_HEADER);
        }
        String origin = request.headers().firstHeader(HttpHeaders.ORIGIN);
        if (origin == null ? originRequired : !origin.equals(publicOrigin())) {
            return Optional.of(ApiReason.FORBIDDEN_ORIGIN);
        }
        if (!acceptedBody(request)) {
            return Optional.of(ApiReason.UNSUPPORTED_MEDIA_TYPE);
        }
        return Optional.empty();
    }

    /** Blank configuration matches no Origin at all: fail closed. */
    private String publicOrigin() {
        String configured = properties.getWeb().getPublicOrigin();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String o = configured.strip();
        return o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
    }

    /** Exactly application/json or multipart/form-data (any parameters); a wildcard or a missing type is not either. */
    private static boolean acceptedBody(ServerRequest request) {
        try {
            return request.headers().contentType()
                    .map(t -> t.equalsTypeAndSubtype(MediaType.APPLICATION_JSON) || t.equalsTypeAndSubtype(MediaType.MULTIPART_FORM_DATA))
                    .orElse(false);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static Mono<ServerResponse> error(ServerRequest request, Throwable e) {
        Optional<ApiReason> reason = ApiReason.of(e);
        if (reason.isPresent()) {
            return reason.get().respond();
        }
        HttpStatusCode status = e instanceof BaymaxException be ? be.status()
                : e instanceof ResponseStatusException rse ? rse.getStatusCode() : null;
        if (status != null) {
            if (status.is5xxServerError()) {
                // a 5xx raised on purpose (storage down, login off) is still an incident: its class is where one starts
                log.error("[baymax-api] {} on {} {} error={}", status.value(), request.method(), route(request), e.getClass().getSimpleName());
                log.debug("[baymax-api] server error detail", e);
            }
            return ServerResponse.status(status).build();
        }
        // the class, never the message: an exception message can carry patient text
        log.error("[baymax-api] unhandled error on {} {} error={}", request.method(), route(request), e.getClass().getSimpleName());
        log.debug("[baymax-api] unhandled error detail", e);
        return ServerResponse.status(500).build();
    }
}
