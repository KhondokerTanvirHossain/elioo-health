package com.elioo.baymax.wa.application.port.in;

import reactor.core.publisher.Mono;

/** F9a's housekeeping of the inbound ledger: what a restart cut off, and how long an unknown sender's key is kept. */
public interface InboundRecoveryUseCase {

    /**
     * Closes, as {@code CUT_OFF}, every message an earlier instance claimed and never answered, and returns how many.
     * Each row is counted by exactly one startup: the next one sees it closed. A Meta re-delivery can still reclaim it.
     */
    Mono<Long> closeCutOff();

    /**
     * Removes the rows of senders with no family older than {@link #UNKNOWN_SENDER_RETENTION}: nothing can delete them on
     * request, and the dedupe needs the key only while Meta retries (7 days).
     */
    Mono<Long> purgeUnknownSenders();

    java.time.Duration UNKNOWN_SENDER_RETENTION = java.time.Duration.ofDays(8);
}
