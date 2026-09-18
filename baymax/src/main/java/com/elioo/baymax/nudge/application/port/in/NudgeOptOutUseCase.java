package com.elioo.baymax.nudge.application.port.in;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** Stop nudges for one patient or a whole family; confirmed back to whoever asked. */
public interface NudgeOptOutUseCase {

    /** The token a link must carry to opt a patient out without a session (an HMAC of the patient id). */
    String token(UUID patientId);

    boolean tokenValid(UUID patientId, String token);

    Mono<Void> optOutPatient(UUID patientId);

    Mono<Void> optOutFamily(UUID patientId);
}
