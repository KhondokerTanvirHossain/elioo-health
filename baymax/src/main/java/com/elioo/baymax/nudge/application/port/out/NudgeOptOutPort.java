package com.elioo.baymax.nudge.application.port.out;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Opt-out, stored on the patient or the family; effective the moment it is written. */
public interface NudgeOptOutPort {

    Mono<Void> optOutPatient(UUID patientId, Instant at);

    Mono<Void> optOutFamily(UUID familyId, Instant at);
}
