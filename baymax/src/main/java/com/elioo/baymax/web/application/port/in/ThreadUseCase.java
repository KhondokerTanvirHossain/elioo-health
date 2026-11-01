package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.ThreadItem;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The চ্যাট thread (plan W5-api): one patient's papers and the messages the provider accepted for the viewer's own
 * account, newest first, a page at a time. Reading it records no view: each card is read through
 * {@link ResultUseCase#card}. A patient this family cannot see is 404; a cursor that is not a previous page's is 400.
 */
public interface ThreadUseCase {

    int PAGE_SIZE = 20;

    Mono<Page> thread(UUID familyId, UUID patientId, String cursor);

    /**
     * Plan CHAT-api: with {@code asks}, the page also lists the viewer's own answered চ্যাট turns about this patient
     * ({@link ThreadItem.Ask}, call999 decided for the viewer). Without it, exactly {@link #thread(UUID, UUID, String)}.
     */
    default Mono<Page> thread(UUID familyId, UUID patientId, String cursor, boolean asks) {
        return thread(familyId, patientId, cursor);
    }

    /** One page: its entries in order, and the cursor of the next page (null on the last). */
    record Page(List<Entry> items, String nextCursor) {
    }

    /**
     * An entry: the item, and for a paper its card (a {@code ResultView} or a {@code ReadingView}, as
     * {@link ResultUseCase#card} answers); null for a message.
     */
    record Entry(ThreadItem item, Object card) {
    }
}
