package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.NowSeen;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The দেখেছি store (DR-41 item 5, table {@code now_ack}). It never decides which papers the banner holds: that is the
 * attention stack's NOW set ({@code AttentionUseCase.urgent}), read by the caller. This port only enriches those ids
 * with what was shown and records what an account holder acknowledged. It never writes {@code document},
 * {@code observation} or {@code outbound_message}.
 */
public interface NowAckPort {

    /**
     * Per id: the stored urgency, the sha256 hex of {@code urgency_reasons::text}, and the latest explanation's state
     * ({@code approved} → {@code released}, none → {@code none}). No filter of its own beyond the ids it is given.
     */
    Flux<NowSeen> seenOf(Collection<UUID> documentIds);

    /** The acknowledgements this account holder (the viewer's own {@code family_account}) recorded on these papers. */
    Flux<NowSeen> ackedBy(UUID viewerFamilyId, Collection<UUID> documentIds);

    /** Records each entry for this account holder; an entry already recorded is left as it is. Emits how many were new. */
    Mono<Long> record(UUID viewerFamilyId, List<NowSeen> seen, Instant at);
}
