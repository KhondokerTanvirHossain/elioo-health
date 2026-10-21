package com.elioo.baymax.outbound.application.port.in;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The family's message for a document, in three separate steps (F4): compose (safety-checked, nothing stored),
 * release (stored; parked or delivered), deliver (the only path to the channel, at most once per row). Before F4 one
 * call composed, saved and delivered, and the WhatsApp layer then sent the body a second time.
 */
public interface ExplainDocumentUseCase {

    /**
     * Composes and safety-checks the family's message for a document, and decides its gate status. NEVER saves and
     * NEVER delivers (F4). EXPLANATION on NEEDS_RETAKE is the retake prompt and on FAILED the FAILURE notice (PR 9);
     * DETAIL exists only for DONE. Empty for any other status. The message may be FAILED_SAFETY with no body.
     */
    Mono<OutboundMessage> composeChecked(UUID documentId, OutboundMessage.Kind kind);

    /** Persists a composed, unsaved message: PENDING → reviewer, FAILED_SAFETY → stored only, RELEASED → delivered. */
    Mono<OutboundMessage> release(OutboundMessage composed);

    /**
     * Hands a STORED, released-or-approved row to delivery, at most once (an atomic claim on the row), and records the
     * outcome (V13/V14). Empty when the claim is lost (the row is being or was delivered). Errors for anything else.
     */
    Mono<OutboundMessage> deliver(OutboundMessage stored);

    /**
     * The post-extraction message: {@code composeChecked(EXPLANATION)} then {@code release}. A FAILED document yields a
     * FAILURE notice; a DONE document never ends in silence: an explanation that failed safety, or whose composition
     * threw, is replaced by the code-only, value-free fallback (PR 9).
     */
    Mono<OutboundMessage> explain(UUID documentId);

    /**
     * The document's DETAIL row: the cached one while its urgency is still current, otherwise composed and STORED
     * (parked if PENDING). Never delivered here — the WhatsApp answer calls {@link #deliver}; the web only shows it.
     */
    Mono<OutboundMessage> detail(UUID documentId);
}
