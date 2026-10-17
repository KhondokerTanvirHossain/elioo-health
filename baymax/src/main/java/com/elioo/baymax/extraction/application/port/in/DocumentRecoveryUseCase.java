package com.elioo.baymax.extraction.application.port.in;

import com.elioo.baymax.extraction.domain.RecoveryReport;
import reactor.core.publisher.Flux;

import java.time.Instant;

/** F9b: finish the documents a restart interrupted. */
public interface DocumentRecoveryUseCase {

    /**
     * Re-runs every RECEIVED/PROCESSING document last updated before {@code cutoff} (the instant this instance
     * started; anything newer is being processed by this instance), one at a time, and reports each.
     */
    Flux<RecoveryReport> recoverInterrupted(Instant cutoff);
}
