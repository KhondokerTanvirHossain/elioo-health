package com.elioo.baymax.web.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * DR-41 item 5: exactly what the NOW banner showed of one paper, the thing a দেখেছি tap acknowledges. {@code urgency} is
 * the stored level ({@code now} on every banner item, null for a paper never assessed); {@code reasonsDigest} the sha256 hex of
 * {@code document.urgency_reasons::text} as it stood when the banner was drawn, so the reasons themselves never leave
 * Java; {@code releaseState} the latest explanation's {@code gate_status} with {@code approved} collapsed to
 * {@code released}, and {@code none} when there is no explanation. A raise, a new reason or a release is a different
 * {@code NowSeen}, so the banner shows again; a re-assessment that changes none of them (which restamps
 * {@code urgency_assessed_at}) is the same one, so it does not. {@code patientId} is the paper's own patient: it is
 * carried, never compared ({@link #sameShownAs}), because the client sends back only the four fields it was given.
 */
public record NowSeen(UUID documentId, UUID patientId, String urgency, String reasonsDigest, String releaseState) {

    public NowSeen {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(reasonsDigest, "reasonsDigest");
        Objects.requireNonNull(releaseState, "releaseState");
    }

    /** The same paper shown the same way: id, level, reasons and release state all equal. */
    public boolean sameShownAs(NowSeen other) {
        return other != null && documentId.equals(other.documentId) && Objects.equals(urgency, other.urgency)
                && reasonsDigest.equals(other.reasonsDigest) && releaseState.equals(other.releaseState);
    }
}
