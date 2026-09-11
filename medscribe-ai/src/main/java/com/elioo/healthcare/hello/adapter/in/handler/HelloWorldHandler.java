package com.elioo.healthcare.hello.adapter.in.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class HelloWorldHandler {

    public Mono<ServerResponse> sayHello(ServerRequest request) {
        return ServerResponse.ok()
                .bodyValue(Map.of("message", "Hello World!"));
    }
}
