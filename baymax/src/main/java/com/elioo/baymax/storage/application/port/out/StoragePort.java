package com.elioo.baymax.storage.application.port.out;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * A private object store (S3-compatible: AWS S3, Supabase Storage, MinIO). Objects are never public;
 * the web timeline reads them through short-lived signed URLs.
 */
public interface StoragePort {

    /** Stores the bytes under the key (server-side encrypted where configured); returns the size stored. */
    Mono<Long> put(String key, byte[] bytes, String contentType);

    Mono<byte[]> get(String key);

    /** Keys under the prefix, in key order. */
    Flux<String> listKeys(String prefix);

    /** A GET URL that stops working after {@code ttl}. */
    Mono<URI> signedGetUrl(String key, Duration ttl);

    /** Deletes every object under a non-blank prefix; returns how many were deleted. */
    Mono<Long> deleteByPrefix(String prefix);
}
