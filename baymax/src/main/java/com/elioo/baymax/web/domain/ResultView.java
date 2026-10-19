package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.util.List;

/**
 * Everything a document page renders, read from stored rows only. {@code urgency} is the document's stored F1 level
 * and is null only while the document is not DONE (or a DONE document could not be assessed). {@code wording} is the
 * released explanation's body and is non-null only when {@code gate == RELEASED} (F3, asserted here so no renderer
 * can show a parked or rejected body).
 *
 * @param retakeOrdinal 1, 2, 3… for a NEEDS_RETAKE document (this patient's streak up to and including it), else 0
 * @param reviewHours   the typical review time in the PENDING copy (spec §4.10 "[N]"), from configuration
 */
public record ResultView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                         Urgency urgency, List<String> urgencyReasons, GateState gate, String wording,
                         int retakeOrdinal, int reviewHours, Instant receivedAt, Instant now) {
    public ResultView {
        if (wording != null && gate != GateState.RELEASED) {
            throw new IllegalArgumentException("F3: model wording only when released or approved, not " + gate);
        }
        if (urgency != null && !"DONE".equals(document.status())) {
            throw new IllegalArgumentException("urgency is shown from DONE onwards, not " + document.status());
        }
        urgencyReasons = urgencyReasons == null ? List.of() : List.copyOf(urgencyReasons);
    }

    public boolean done() {
        return "DONE".equals(document.status());
    }
}
