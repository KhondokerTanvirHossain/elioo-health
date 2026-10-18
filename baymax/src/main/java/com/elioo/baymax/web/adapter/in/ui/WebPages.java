package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** One screen's routes. BaymaxWebUiRouter only composes these, in {@link #order()}; it routes nothing itself. */
public interface WebPages {

    /** Lower first. Open pages 10–19, signed-in pages 20–89, assets 90. */
    int order();

    /** {@code requireSession} is the router's session filter; a handler applies it to its signed-in routes only. */
    RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession);
}
