package com.elioo.baymax.common.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Turns errors from Baymax handlers into {@code {"status": n, "reason": "...", "message": "..."}}.
 * {@link BaymaxException} keeps its status and reason (402 free-tier, 404, 409, 400); malformed input is 400;
 * anything else is 500 with a generic message and a full stack trace in the log, never in the response.
 */
@Slf4j
@Component
public class ErrorResponseFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        // defer so that an exception thrown before the handler returns a Mono is mapped too
        return Mono.defer(() -> next.handle(request)).onErrorResume(e -> respond(request, e));
    }

    private static Mono<ServerResponse> respond(ServerRequest request, Throwable e) {
        if (e instanceof BaymaxException be) {
            return body(be.status(), be.reason(), be.getMessage());
        }
        if (e instanceof ServerWebInputException || e instanceof IllegalArgumentException) {
            return body(HttpStatus.BAD_REQUEST, "invalid_request", rootMessage(e));
        }
        if (e instanceof ResponseStatusException rse) {
            return body(rse.getStatusCode(), "error", rse.getReason() == null ? "request failed" : rse.getReason());
        }
        log.error("[baymax] unhandled error on {} {}: {}", request.method(), request.path(), e.getMessage(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    private static Mono<ServerResponse> body(HttpStatusCode status, String reason, String message) {
        return ServerResponse.status(status).bodyValue(Map.of(
                "status", status.value(),
                "reason", reason,
                "message", message == null ? "" : message));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? "invalid request" : m.lines().findFirst().orElse("invalid request");
    }
}
