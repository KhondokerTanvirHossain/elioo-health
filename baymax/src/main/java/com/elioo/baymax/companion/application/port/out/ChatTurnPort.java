package com.elioo.baymax.companion.application.port.out;

import com.elioo.baymax.companion.domain.ChatOutcome;
import com.elioo.baymax.companion.domain.ChatTurn;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** The চ্যাট turns (V29): reserved before the answer is made, completed with exactly what the family was shown. */
public interface ChatTurnPort {

    /**
     * Reserves a turn counted against the account's daily cap, atomically: of any number of concurrent reservations of one
     * account at the boundary, exactly as many succeed as the cap has room for. Empty when the account already holds
     * {@code cap} counted turns created at or after {@code dayStart}.
     */
    Mono<UUID> reserveCounted(UUID familyId, UUID patientId, String lang, String question, Instant dayStart, int cap);

    /** Reserves a turn the cap never counts: an emergency, a limit or a declined turn. */
    Mono<UUID> reserveUncounted(UUID familyId, UUID patientId, String lang, String question);

    /** Stores the turn's outcome: the shown answer, its kind and handler, and the model's calls, tokens and cost. */
    Mono<Void> complete(UUID turnId, ChatOutcome outcome);

    /**
     * The answered turns this account asked about this patient, newest first, each of whose papers ({@code document_ids})
     * all still exist (S10): a turn citing a deleted paper is not listed, a turn citing none is.
     */
    Flux<ChatTurn> answered(UUID familyId, UUID patientId);
}
