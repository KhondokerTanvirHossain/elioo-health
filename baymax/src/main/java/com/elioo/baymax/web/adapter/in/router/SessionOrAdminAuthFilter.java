package com.elioo.baymax.web.adapter.in.router;

import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * The document routes serve two callers: a family's browser (session cookie, scoped to what the family may
 * see) and the operator (admin token, unscoped). A request carrying the admin header is judged as admin and
 * only as admin; anything else must carry a live session. Handlers read
 * {@link SessionAuthFilter#session(ServerRequest)}: null means admin.
 */
@Component
@RequiredArgsConstructor
public class SessionOrAdminAuthFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final AdminAuthFilter admin;
    private final SessionAuthFilter session;

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        if (request.headers().firstHeader(AdminAuthFilter.HEADER) != null) {
            return admin.filter(request, next);
        }
        return session.filter(request, next);
    }
}
