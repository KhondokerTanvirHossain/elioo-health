package com.elioo.baymax.nudge.application.port.out;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Opt-out, stored on the patient or the family; effective the moment it is written. */
public interface NudgeOptOutPort {

    Mono<Void> optOutPatient(UUID patientId, Instant at);

    Mono<Void> optOutFamily(UUID familyId, Instant at);

    /** Whether the family's own stop is set ({@code family_account.nudges_opted_out_at}); empty for no such family. */
    Mono<Boolean> familyOptedOut(UUID familyId);
}
