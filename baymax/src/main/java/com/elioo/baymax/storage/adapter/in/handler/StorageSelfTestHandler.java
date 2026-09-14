package com.elioo.baymax.storage.adapter.in.handler;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/v1/baymax/admin/storage/selftest}: put a tiny JPEG under {@code _selftest/}, presign it,
 * fetch the URL over HTTP, delete it, and report each step. Proves bucket, credentials (instance role in
 * production) and presigned URLs from inside the running container. 200 when every step is ok, else 503.
 */
@Slf4j
@Component
public class StorageSelfTestHandler {

    static final byte[] PROBE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, (byte) 0xFF, (byte) 0xD9};

    private final ObjectProvider<StoragePort> storage;
    private final BaymaxProperties properties;
    private final WebClient http;

    @Autowired
    public StorageSelfTestHandler(ObjectProvider<StoragePort> storage, BaymaxProperties properties) {
        this(storage, properties, WebClient.create());
    }

    StorageSelfTestHandler(ObjectProvider<StoragePort> storage, BaymaxProperties properties, WebClient http) {
        this.storage = storage;
        this.properties = properties;
        this.http = http;
    }

    public Mono<ServerResponse> selfTest(ServerRequest request) {
        StoragePort port = storage.getIfAvailable();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("bucket", properties.getStorage().getBucket());
        report.put("credentials", properties.getStorage().getCredentials().name().toLowerCase());
        Map<String, String> steps = new LinkedHashMap<>();
        report.put("steps", steps);
        if (port == null) {
            steps.put("put", "failed: storage not configured (baymax.storage.bucket is blank)");
            return respond(report, steps);
        }
        String key = "_selftest/" + UUID.randomUUID() + ".jpg";
        report.put("key", key);

        return port.put(key, PROBE, "image/jpeg")
                .doOnNext(size -> steps.put("put", "ok (" + size + " bytes)"))
                .onErrorResume(e -> fail(steps, "put", e))
                .flatMap(ignored -> port.signedGetUrl(key, Duration.ofSeconds(60))
                        .doOnNext(url -> steps.put("presign", "ok"))
                        .onErrorResume(e -> fail(steps, "presign", e)))
                .flatMap(url -> fetch(url)
                        .doOnNext(body -> steps.put("get", Arrays.equals(body, PROBE) ? "ok" : "failed: body differs"))
                        .onErrorResume(e -> fail(steps, "get", e)))
                .then(Mono.defer(() -> port.deleteByPrefix(key)
                        .doOnNext(n -> steps.put("delete", n == 1 ? "ok" : "failed: deleted " + n + " objects"))
                        .onErrorResume(e -> fail(steps, "delete", e))))
                .then(Mono.defer(() -> respond(report, steps)));
    }

    private Mono<byte[]> fetch(URI url) {
        return http.get().uri(url).retrieve().bodyToMono(byte[].class);
    }

    private static <T> Mono<T> fail(Map<String, String> steps, String step, Throwable e) {
        log.warn("[baymax] storage selftest step {} failed: {}", step, e.getMessage());
        steps.put(step, "failed: " + e.getMessage());
        return Mono.empty();
    }

    private static Mono<ServerResponse> respond(Map<String, Object> report, Map<String, String> steps) {
        boolean ok = steps.size() == 4 && steps.values().stream().allMatch(v -> v.startsWith("ok"));
        report.put("ok", ok);
        return ServerResponse.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).bodyValue(report);
    }
}
