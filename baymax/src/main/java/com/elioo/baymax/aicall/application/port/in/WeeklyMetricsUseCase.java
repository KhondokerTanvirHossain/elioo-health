package com.elioo.baymax.aicall.application.port.in;

import reactor.core.publisher.Mono;

import java.time.Instant;

/** The weekly pilot-log export: per-document AI cost/model/confidence and per-family activity, as CSV. */
public interface WeeklyMetricsUseCase {

    Mono<String> weeklyCsv(Instant from, Instant to);
}
