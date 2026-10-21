package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.NowBanner;
import com.elioo.baymax.web.domain.NowSeen;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The global NOW banner and its দেখেছি (DR-41 item 5). {@code viewerFamilyId} is the session's own
 * {@code family_account}: a share member is a separate account holder from the owner, and neither's tap hides the
 * banner for the other (Part B decision 8, plan B3).
 */
public interface NowBannerUseCase {

    /** Every NOW card of every patient this account may see, each marked with this account holder's ack. Records nothing. */
    Mono<NowBanner> banner(UUID viewerFamilyId);

    /**
     * Records the entries of {@code seen} that equal a current banner item exactly (id, urgency, reasons digest, release
     * state); any other entry is ignored, so a tap on a banner that changed after it was drawn acknowledges nothing new,
     * and an id this account cannot see is never written. Writes nothing but {@code now_ack}. Answers the banner as it
     * stands afterwards.
     */
    Mono<NowBanner> acknowledge(UUID viewerFamilyId, List<NowSeen> seen);
}
