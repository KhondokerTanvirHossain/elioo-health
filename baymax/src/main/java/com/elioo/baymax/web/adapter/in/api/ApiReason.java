package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Why an {@code /app/api} request was refused. The body is always {@code {"reason":"<NAME>"}} and nothing else: no
 * developer message (it can carry a filename or a medicine), no status echo. React maps the name to copy; Java never
 * sends prose. Additive only (rebuild plan A2): a name, once shipped, keeps its meaning and its status.
 */
public enum ApiReason {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    MISSING_HEADER(HttpStatus.FORBIDDEN),
    FORBIDDEN_ORIGIN(HttpStatus.FORBIDDEN),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID(HttpStatus.BAD_REQUEST);

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
     * malformed input. Empty for anything else (a 403 or a 402 has no name yet; the PR that first raises one on this
     * namespace adds it), which the guard answers with the bare status and no body.
     */
    static Optional<ApiReason> of(Throwable e) {
        if (e instanceof BaymaxException be) {
            return byStatus(be.status().value());
        }
        if (e instanceof ServerWebInputException || e instanceof IllegalArgumentException) {
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
