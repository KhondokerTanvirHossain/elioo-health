package com.elioo.baymax.aicall.application.port.out;

import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Persistence of the per-call AI cost log and the roll-ups the pilot log is built from. */
public interface AiCallLogPort {

    /** Writes one row; returns the record with its generated id. */
    Mono<AiCallRecord> save(AiCallRecord record);

    /** One row per document (plus one for untied calls) for calls created in {@code [from, to)}. */
    Flux<DocumentAiCost> perDocument(Instant from, Instant to);

    /** Output tokens of every successful extract call in the window, one value per call, for the cost columns. */
    Flux<Integer> extractOutputTokens(Instant from, Instant to);
}
