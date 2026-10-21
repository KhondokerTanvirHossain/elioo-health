package com.elioo.baymax.web.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The global NOW banner for one account holder (rebuild plan Global Constraints, DR-41 item 5): every NOW card of every
 * patient this account may see, in the patients' order and then the stack's, each with what was shown
 * ({@link NowSeen}) and whether this account holder acknowledged exactly that. {@code call999} is F7's rule for the
 * viewer's own phone. The banner shows while any paper is not acknowledged; দেখেছি hides only the banner, never the
 * paper's NOW block, the patient's red mark or a WhatsApp message.
 */
public record NowBanner(List<Item> documents, boolean call999) {

    public NowBanner {
        documents = List.copyOf(documents);
    }

    /**
     * One NOW paper on the banner: what was shown, its type and filed day (D2) with the stack card's uncertain mark
     * ({@code dateUncertain}, the read model's {@code timeline_date_uncertain}, never true on an upload day), and this
     * account holder's ack.
     */
    public record Item(NowSeen seen, String documentType, LocalDate date, boolean dateIsUploadDay, boolean dateUncertain, boolean acked) {
    }

    /** Any paper not acknowledged: the banner shows. None at all: nothing to show. */
    public boolean show() {
        return documents.stream().anyMatch(i -> !i.acked());
    }
}
