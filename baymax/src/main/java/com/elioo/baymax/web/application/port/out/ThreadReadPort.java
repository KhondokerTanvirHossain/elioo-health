package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.ThreadItem;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** The চ্যাট thread's read model (plan W5-api): read only, after the caller has decided the patient is visible. */
public interface ThreadReadPort {

    /**
     * One page of {@code patientId}'s thread as {@code viewerFamilyId} sees it, newest first, strictly after {@code after}:
     * every paper of the patient, and every message to the viewer's own account (S4) about this patient that the
     * provider accepted ({@code delivery_status = 'sent'}, so a wamid by V14; a logged {@code sent_at} is not a delivery,
     * S11) and whose paper, if it names one, still exists (S10), except the newest explanation of a DONE paper of this
     * patient, which that paper's card already carries as its {@code wording}. At most {@code limit} entries.
     */
    Flux<ThreadItem> page(UUID viewerFamilyId, UUID patientId, ThreadItem.Cursor after, int limit);
}
