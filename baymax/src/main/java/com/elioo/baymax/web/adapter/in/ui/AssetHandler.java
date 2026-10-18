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
import java.util.List;
import java.util.Set;

/** {@code /assets/{file}}: the stylesheet and the landing images from the jar. */
@Component
public class AssetHandler implements WebPages {

    /** Files under classpath {@code baymax/ui/} that {@code /assets/{file}} may serve; nothing else leaves the jar. */
    static final Set<String> ASSETS = Set.of("baymax.css", "favicon.svg", "mio.webp", "mio-head.webp", "medioo.webp", "paper-lab.svg", "paper-rx.svg", "mio.png", "medioo.png", "medioo.svg");

    /** The stylesheet's parts, in the order they are served as /assets/baymax.css. Each area appends only to its own. */
    static final List<String> CSS_PARTS = List.of("00-tokens.css", "10-base.css", "20-shell.css", "30-family.css",
            "40-papers.css", "50-chat.css", "60-result.css", "70-upload.css", "90-landing.css");

    private static final byte[] STYLESHEET = concat(CSS_PARTS);

    static byte[] stylesheet() {
        return STYLESHEET.clone();
    }

    /** The cache-busting version of a served asset, from the bytes served (the stylesheet: the concatenation). */
    static String version(String file) {
        byte[] bytes = "baymax.css".equals(file) ? STYLESHEET : read(file);
        return Integer.toHexString(java.util.Arrays.hashCode(bytes == null ? new byte[0] : bytes));
    }

    /** The parts under classpath {@code baymax/ui/css/}, joined in order; a listed part missing from the jar is loud. */
    private static byte[] concat(List<String> parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String part : parts) {
            byte[] bytes = read("css/" + part);
            if (bytes == null) {
                throw new IllegalStateException("stylesheet part css/" + part + " is not in the jar");
            }
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    /** A file under classpath {@code baymax/ui/}, or null when it is not in the jar. */
    private static byte[] read(String file) {
        InputStream in = AssetHandler.class.getResourceAsStream("/baymax/ui/" + file);
        if (in == null) {
            return null;
        }
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
        byte[] bytes = "baymax.css".equals(file) ? stylesheet() : read(file);
        if (bytes == null) {
            return ServerResponse.notFound().build();
        }
        MediaType type = file.endsWith(".css") ? new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8)
                : file.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
                : file.endsWith(".webp") ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG;
        return ServerResponse.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .eTag("\"" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"").bodyValue(bytes);
    }
}
