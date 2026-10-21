package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Why an {@code /app/api} request was refused. A refusal with a name has the body {@code {"reason":"<NAME>"}} and nothing
 * else: no developer message (it can carry a filename or a medicine), no status echo. React maps the name to copy; Java
 * never sends prose. A status with no name yet (a 403 owner-only, a 402 free tier, any 5xx) is answered bodiless, with
 * the bare status; the PR that first raises one on this namespace adds its name here. Additive only (rebuild plan A2):
 * a name, once shipped, keeps its meaning and its status.
 */
public enum ApiReason {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    MISSING_HEADER(HttpStatus.FORBIDDEN),
    FORBIDDEN_ORIGIN(HttpStatus.FORBIDDEN),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID(HttpStatus.BAD_REQUEST),
    /** Sign-in request: the typed number is not one {@code PhoneDisplay.normalise} accepts. */
    INVALID_NUMBER(HttpStatus.BAD_REQUEST),
    /** Sign-in verify with no valid {@code baymax_login} cookie: start again from the number. */
    SIGN_IN_RESTART(HttpStatus.BAD_REQUEST),
    /**
     * The code refusals, 400 and never 401: 401 means "no session" and sends the client to sign-in, which must never
     * fire on a code typed on the sign-in page itself. Exactly as fine as the HTML page's copy: WRONG and NO_CODE are
     * both {@code CODE_WRONG}, as both are {@code login.bad.wrong} there.
     */
    CODE_WRONG(HttpStatus.BAD_REQUEST),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST),
    CODE_BURNED(HttpStatus.BAD_REQUEST),
    CODE_FORMAT(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ApiReason(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public Mono<ServerResponse> respond() {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", name()));
    }

    /**
     * The reason for an error a handler raised, by its status: a {@link BaymaxException}'s 401 / 404 / 400 / 415, or
     * input the request itself got wrong (a body the decoder could not read, a binding failure). Empty for anything
     * else (a 403 or a 402 has no name yet), which the guard answers with the bare status and no body.
     *
     * <p>A bare {@link IllegalArgumentException} is deliberately NOT {@code INVALID}: thrown from deep in a service (an
     * unknown stored enum value, a precondition, {@code UUID.fromString} on stored data) it is a server fault, and
     * calling it the client's would hide a 500 behind "your request was invalid" with no ERROR line. It falls through
     * to the guard's 500. A handler that means a 400 says so with {@link BaymaxException#badRequest}.
     */
    static Optional<ApiReason> of(Throwable e) {
        if (e instanceof BaymaxException be) {
            return byStatus(be.status().value());
        }
        if (e instanceof ServerWebInputException || e instanceof DecodingException) {
            return Optional.of(INVALID);
        }
        if (e instanceof ResponseStatusException rse) {
            // e.g. a body the decoder cannot read as the route's type: Spring's 415 or 400
            return byStatus(rse.getStatusCode().value());
        }
        return Optional.empty();
    }

    private static Optional<ApiReason> byStatus(int status) {
        return switch (status) {
            case 401 -> Optional.of(UNAUTHENTICATED);
            case 404 -> Optional.of(NOT_FOUND);
            case 400 -> Optional.of(INVALID);
            case 415 -> Optional.of(UNSUPPORTED_MEDIA_TYPE);
            default -> Optional.empty();
        };
    }
}
