package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Set;

/** {@code /assets/{file}}: the stylesheet and the landing images from the jar. */
@Component
public class AssetHandler implements WebPages {

    /** Files under classpath {@code baymax/ui/} that {@code /assets/{file}} may serve; nothing else leaves the jar. */
    static final Set<String> ASSETS = Set.of("baymax.css", "favicon.svg", "mio.webp", "mio-head.webp", "medioo.webp", "paper-lab.svg", "paper-rx.svg", "mio.png", "medioo.png", "medioo.svg");

    @Override
    public int order() {
        return 90;   // assets
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET("/assets/{file}", this::asset)
                .build();
    }

    /** {@code GET /assets/{file}}: the stylesheet and the landing images from the jar, cacheable for a day. */
    public Mono<ServerResponse> asset(ServerRequest request) {
        String file = request.pathVariable("file");
        if (!ASSETS.contains(file)) {
            return ServerResponse.notFound().build();
        }
        InputStream in = AssetHandler.class.getResourceAsStream("/baymax/ui/" + file);
        if (in == null) {
            return ServerResponse.notFound().build();
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MediaType type = file.endsWith(".css") ? new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8)
                : file.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
                : file.endsWith(".webp") ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG;
        return ServerResponse.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .eTag("\"" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"").bodyValue(bytes);
    }
}
