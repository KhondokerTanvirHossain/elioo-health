package com.elioo.healthcare.hello.adapter.in.router;

import com.elioo.healthcare.hello.adapter.in.handler.HelloWorldHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@Configuration
@RequiredArgsConstructor
public class HelloWorldRouter {

    private final HelloWorldHandler helloWorldHandler;

    @Bean
    public RouterFunction<ServerResponse> helloRoutes() {
        return RouterFunctions.route(GET("/api/hello"), helloWorldHandler::sayHello);
    }
}
