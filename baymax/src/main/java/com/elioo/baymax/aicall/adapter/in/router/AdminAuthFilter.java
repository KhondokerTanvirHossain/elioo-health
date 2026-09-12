package com.elioo.baymax.aicall.adapter.in.router;

import com.elioo.baymax.config.BaymaxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Shared-secret gate for {@code /api/v1/baymax/admin/**}: the {@value #HEADER} header must equal
 * {@code baymax.admin.token}. Fails closed (503) when no token is configured. The presented value is
 * never logged.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String HEADER = "X-Baymax-Admin-Token";

    private final BaymaxProperties properties;

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        String configured = properties.getAdmin().getToken();
        if (!StringUtils.hasText(configured)) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyValue(Map.of("error", "baymax.admin.token is not configured"));
        }
        String presented = request.headers().firstHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                    .bodyValue(Map.of("error", "missing or invalid " + HEADER));
        }
        return next.handle(request);
    }
}
