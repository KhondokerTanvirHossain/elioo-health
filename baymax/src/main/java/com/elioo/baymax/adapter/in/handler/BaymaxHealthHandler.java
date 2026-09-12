package com.elioo.baymax.adapter.in.handler;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Liveness of the Baymax module itself (distinct from the actuator health of the whole app).
 * Answering at all proves the module was loaded, i.e. {@code baymax.enabled=true}.
 */
@Component
public class BaymaxHealthHandler {

    public Mono<ServerResponse> health(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "UP", "module", "baymax"));
    }
}
