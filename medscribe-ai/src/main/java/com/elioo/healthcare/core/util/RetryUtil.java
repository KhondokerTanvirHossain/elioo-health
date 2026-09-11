package com.elioo.healthcare.core.util;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Utility class for retry logic with exponential backoff.
 *
 * <p>Use Cases:</p>
 * <ul>
 *   <li>Database replication lag - query replica before data is replicated</li>
 *   <li>Transaction commit race conditions - query before transaction commits</li>
 *   <li>Eventual consistency scenarios - data not immediately available</li>
 * </ul>
 *
 * <p>Example Usage:</p>
 * <pre>{@code
 * return repository.findById(id)
 *     .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500), "Finding record " + id))
 *     .switchIfEmpty(Mono.error(new NotFoundException()));
 * }</pre>
 */
@Slf4j
public class RetryUtil {

    /**
     * Retry on empty Mono with exponential backoff.
     *
     * <p>This is useful when querying for data that should exist but might not be immediately
     * available due to database replication lag, transaction commit timing, or eventual consistency.</p>
     *
     * <p>Retry Schedule (with default 500ms initial delay):</p>
     * <ul>
     *   <li>Attempt 1: Immediate</li>
     *   <li>Attempt 2: After 500ms</li>
     *   <li>Attempt 3: After 1000ms (cumulative: 1.5s)</li>
     *   <li>Attempt 4: After 2000ms (cumulative: 3.5s)</li>
     * </ul>
     *
     * @param maxAttempts Maximum number of retry attempts (excluding initial attempt)
     * @param initialDelay Initial delay before first retry
     * @param operationDescription Description for logging
     * @param <T> Type of Mono value
     * @return Transformer function that adds retry logic
     */
    public static <T> java.util.function.Function<Mono<T>, Mono<T>> retryOnEmpty(
            int maxAttempts,
            Duration initialDelay,
            String operationDescription) {

        return mono -> mono
                .repeatWhenEmpty(maxAttempts, repeat -> repeat
                        .take(maxAttempts)  // CRITICAL: Limit number of repeats to prevent infinite loop
                        .doOnNext(tick -> {
                            long attempt = tick + 1;
                            Duration delay = initialDelay.multipliedBy((long) Math.pow(2, tick));

                            log.debug("{} - Data not found, retrying (attempt {}/{}) after {}ms",
                                    operationDescription, attempt, maxAttempts, delay.toMillis());
                        })
                        .delayElements(initialDelay)
                );
    }

    /**
     * Retry on empty Mono with default settings.
     * Default: 3 retries with 500ms initial delay (exponential backoff).
     *
     * @param operationDescription Description for logging
     * @param <T> Type of Mono value
     * @return Transformer function that adds retry logic
     */
    public static <T> java.util.function.Function<Mono<T>, Mono<T>> retryOnEmpty(String operationDescription) {
        return retryOnEmpty(3, Duration.ofMillis(500), operationDescription);
    }

    /**
     * Retry on error with exponential backoff.
     *
     * <p>Use this for transient errors like network issues, timeouts, or temporary service unavailability.</p>
     *
     * @param maxAttempts Maximum number of retry attempts
     * @param initialDelay Initial delay before first retry
     * @param operationDescription Description for logging
     * @param <T> Type of Mono value
     * @return Transformer function that adds retry logic
     */
    public static <T> java.util.function.Function<Mono<T>, Mono<T>> retryOnError(
            int maxAttempts,
            Duration initialDelay,
            String operationDescription) {

        return mono -> mono.retryWhen(
                Retry.backoff(maxAttempts, initialDelay)
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(retrySignal -> {
                            log.warn("{} - Error occurred, retrying (attempt {}/{}) after {}ms: {}",
                                    operationDescription,
                                    retrySignal.totalRetries() + 1,
                                    maxAttempts,
                                    retrySignal.totalRetriesInARow(),
                                    retrySignal.failure().getMessage());
                        })
        );
    }

    /**
     * Retry on specific exception types.
     *
     * @param maxAttempts Maximum number of retry attempts
     * @param initialDelay Initial delay before first retry
     * @param retryableExceptions Exception types that should trigger retry
     * @param operationDescription Description for logging
     * @param <T> Type of Mono value
     * @return Transformer function that adds retry logic
     */
    @SafeVarargs
    public static <T> java.util.function.Function<Mono<T>, Mono<T>> retryOnException(
            int maxAttempts,
            Duration initialDelay,
            String operationDescription,
            Class<? extends Throwable>... retryableExceptions) {

        return mono -> mono.retryWhen(
                Retry.backoff(maxAttempts, initialDelay)
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(throwable -> {
                            for (Class<? extends Throwable> exceptionClass : retryableExceptions) {
                                if (exceptionClass.isInstance(throwable)) {
                                    return true;
                                }
                            }
                            return false;
                        })
                        .doBeforeRetry(retrySignal -> {
                            log.warn("{} - Retryable error occurred, retrying (attempt {}/{}): {}",
                                    operationDescription,
                                    retrySignal.totalRetries() + 1,
                                    maxAttempts,
                                    retrySignal.failure().getMessage());
                        })
        );
    }
}
