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

    /**
     * The same page, and with {@code asks} also every answered চ্যাট turn this viewer's own account asked about this patient
     * whose cited papers all still exist (S10), as {@link ThreadItem.Ask} (plan CHAT-api), interleaved by the same order.
     * Without {@code asks} it is exactly {@link #page(UUID, UUID, ThreadItem.Cursor, int)}.
     */
    default Flux<ThreadItem> page(UUID viewerFamilyId, UUID patientId, ThreadItem.Cursor after, int limit, boolean asks) {
        return asks ? Flux.error(new UnsupportedOperationException("this read model lists no asks"))
                : page(viewerFamilyId, patientId, after, limit);
    }
}
