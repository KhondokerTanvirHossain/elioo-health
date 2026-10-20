package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.AttentionItem;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The চ্যাট attention stack (spec §4.6): what needs attention for one patient, in code-urgency order, from stored F1
 * levels, the papers not finished and the doctor's own dates. Zero model calls.
 */
public interface AttentionUseCase {

    /** The stack for one patient, highest first; empty when nothing needs attention. A patient this family cannot see is a 404. */
    Mono<List<AttentionItem>> attention(UUID familyId, UUID patientId);

    /**
     * The কাগজপত্র strip: only the stack's NOW and THIS_WEEK papers, in its order, read alone (no in-flight or
     * follow-up query). A patient this family cannot see is a 404.
     */
    Mono<List<AttentionItem.Urgent>> urgent(UUID familyId, UUID patientId);
}
