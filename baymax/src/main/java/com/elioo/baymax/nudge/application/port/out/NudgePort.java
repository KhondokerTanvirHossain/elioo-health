package com.elioo.baymax.nudge.application.port.out;

import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeCount;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** The nudge ledger: dedupe keys and outcomes. */
public interface NudgePort {

    Mono<Nudge> save(Nudge nudge);

    /** True when this trigger has ever produced a row for this patient — whatever became of it. */
    Mono<Boolean> exists(UUID patientId, NudgeRule rule, String triggerKey);

    /** Nudges that count against the caps (SENT or GATED) for a patient since an instant. */
    Mono<Long> countedSince(UUID patientId, Instant since);

    /** The most recent row of a rule for a patient, any status. */
    Mono<Nudge> latest(UUID patientId, NudgeRule rule);

    /** HELD rows whose hold has expired. */
    Flux<Nudge> heldDueBy(Instant now);

    Mono<Nudge> resolve(UUID nudgeId, NudgeStatus status, String dropReason, UUID messageId, Instant resolvedAt);

    Flux<NudgeCount> counts(Instant from, Instant to);
}
