package com.elioo.baymax.outbound.application.port.in;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ExplainDocumentUseCase {

    /**
     * Composes the family's message for a finished document: the retake prompt for NEEDS_RETAKE, the short
     * Bangla explanation for DONE. Empty for any other status. The returned message may be PENDING (gated),
     * RELEASED, or FAILED_SAFETY with no body.
     */
    Mono<OutboundMessage> explain(UUID documentId);

    /** The longer explanation, on request; same safety rules and the same length cap. */
    Mono<OutboundMessage> detail(UUID documentId);
}
